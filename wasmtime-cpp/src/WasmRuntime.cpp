/**
 * Implementation of WasmRuntime.
 * Handles Android-specific Wasmtime configuration, caching logic, and thread safety.
 *
 * 2025-12-02
 * @author crowforkotlin / crowforkotlin@gmail.com
 */

#include "WasmRuntime.h"
#include "WasmLogger.h"
#include "WasmFileUtils.h" // Assuming this helper handles std::ifstream logic

/**
 * Access Singleton Instance.
 *
 * 2025-12-02
 * @author crowforkotlin
 */
WasmRuntime& WasmRuntime::getInstance() {
    static WasmRuntime instance;
    return instance;
}

/**
 * Destructor.
 * Ensures all resources are released when the runtime is destroyed.
 *
 * 2025-12-02
 * @author crowforkotlin
 */
WasmRuntime::~WasmRuntime() {
    releaseEngine();
}

/**
 * Creates the Wasmtime Configuration.
 * 
 * Critical Android Settings:
 * 1. Signals-based traps DISABLED: To prevent conflicts with Android ART signal handlers (SIGSEGV).
 * 2. Memory Guard Size = 0: To prevent VSS (Virtual Set Size) OOM on 32-bit or limited devices.
 * 3. GC / Exceptions: Enabled for Kotlin/Wasm support.
 *
 * 2025-12-02
 * @author crowforkotlin
 */
wasm_config_t* WasmRuntime::createConfig() {
    wasm_config_t* conf = wasm_config_new();
    
    // Feature Flags for Kotlin/Wasm
    wasmtime_config_wasm_gc_set(conf, true);
    wasmtime_config_wasm_function_references_set(conf, true);
    wasmtime_config_wasm_exceptions_set(conf, true);
    
    // Optimization: Disable SIMD if not strictly needed (improves compatibility)
    wasmtime_config_wasm_simd_set(conf, false);
    wasmtime_config_wasm_relaxed_simd_set(conf, false);
    
    // [CRITICAL] Disable signal handlers to avoid crash conflicts with Android Runtime (ART)
    wasmtime_config_signals_based_traps_set(conf, false);
    
    // [CRITICAL] Set guard pages to 0 to minimize Virtual Memory usage (prevents OOM)
    wasmtime_config_memory_guard_size_set(conf, 0);
    
    // Set max stack size (512KB is usually sufficient for mobile logic)
    wasmtime_config_max_wasm_stack_set(conf, 512 * 1024);
    
    // Compiler Optimization Strategy
    // Optimize for Speed and Binary Size
    wasmtime_config_cranelift_opt_level_set(conf, WASMTIME_OPT_LEVEL_SPEED_AND_SIZE);
    wasmtime_config_cranelift_debug_verifier_set(conf, false);

    return conf;
}

/**
 * Initialize the global Engine.
 * Thread-safe (Write Lock).
 *
 * 2025-12-02
 * @author crowforkotlin
 */
void WasmRuntime::initEngine() {
    std::unique_lock<std::shared_mutex> lock(resourceMutex);
    if (!engine) {
        auto conf = createConfig();
        engine = wasm_engine_new_with_config(conf);
        if (engine) {
            LOGI("Wasm Engine Initialized successfully.");
        } else {
            LOGE("Failed to initialize Wasm Engine.");
        }
    }
}

/**
 * Release Engine and clean up all caches.
 * Order of destruction is critical: Sessions -> Modules -> Engine.
 *
 * 2025-12-02
 * @author crowforkotlin
 */
void WasmRuntime::releaseEngine() {
    std::unique_lock<std::shared_mutex> lock(resourceMutex);
    
    // 1. Destroy all active sessions
    for (auto& kv : sessionCache) {
        if (kv.second) delete kv.second;
    }
    sessionCache.clear();

    // 2. Destroy all compiled modules
    for (auto& kv : moduleCache) {
        if (kv.second) wasmtime_module_delete(kv.second);
    }
    moduleCache.clear();

    // 3. Destroy Engine
    if (engine) {
        wasm_engine_delete(engine);
        engine = nullptr;
        LOGI("Wasm Engine Released.");
    }
}

/**
 * Load and Compile/Deserialize a Module.
 * Implements Double-Checked Locking for performance.
 *
 * 2025-12-02
 * @author crowforkotlin
 */
wasmtime_module_t* WasmRuntime::loadModule(const std::string& key, const std::string& filePath, bool isJit) {
    // 1. First Check (Read Lock) - Fast path
    {
        std::shared_lock<std::shared_mutex> lock(resourceMutex);
        if (!engine) {
            LOGE("Engine not initialized. Call initEngine() first.");
            return nullptr;
        }
        if (moduleCache.count(key)) return moduleCache[key];
    }

    // 2. Load File Data (IO Operation - performed without holding the lock)
    // This prevents blocking other threads while reading large files (50MB+).
    std::vector<uint8_t> data = Utils::readFile(filePath);
    if (data.empty()) {
        LOGE("[wasmtime] Failed to read module file: %s", filePath.c_str());
        return nullptr;
    } else {
        LOGI("[wasmtime] Success reading module file: %s (%zu bytes)", filePath.c_str(), data.size());
    }

    // 3. Second Check & Compile (Write Lock)
    std::unique_lock<std::shared_mutex> lock(resourceMutex);
    
    // Safety check: Engine might have been released during IO
    if (!engine) return nullptr; 
    // Double check: Another thread might have loaded it while we were reading the file
    if (moduleCache.count(key)) return moduleCache[key]; 

    wasmtime_module_t* module = nullptr;
    wasmtime_error_t* error = nullptr;

    if (isJit) {
        // Compile from source (.wasm)
        LOGI("Compiling Wasm source...");
        error = wasmtime_module_new(engine, data.data(), data.size(), &module);
    } else {
        // Deserialize precompiled (.cwasm)
        LOGI("Deserializing Precompiled module...");
        error = wasmtime_module_deserialize(engine, data.data(), data.size(), &module);
    }

    if (error) {
        wasm_byte_vec_t msg;
        wasmtime_error_message(error, &msg);
        LOGE("Module Load Error: %s", msg.data);
        wasm_byte_vec_delete(&msg);
        wasmtime_error_delete(error);
        return nullptr;
    }

    moduleCache[key] = module;
    LOGI("Module Loaded and Cached: %s", key.c_str());
    return module;
}

/**
 * Retrieve cached module.
 *
 * 2025-12-02
 * @author crowforkotlin
 */
wasmtime_module_t* WasmRuntime::getModule(const std::string& key) {
    std::shared_lock<std::shared_mutex> lock(resourceMutex);
    auto it = moduleCache.find(key);
    return (it != moduleCache.end()) ? it->second : nullptr;
}

/**
 * Get or Create a Session.
 *
 * 2025-12-02
 * @author crowforkotlin
 */
WasmSession* WasmRuntime::getSession(const std::string& key) {
    // 1. Try to get existing session (Read Lock)
    {
        std::shared_lock<std::shared_mutex> lock(resourceMutex);
        auto it = sessionCache.find(key);
        if (it != sessionCache.end()) return it->second;
    }

    // Note: We need to release the read lock before acquiring the write lock to prevent deadlocks.
    // However, we need to ensure the module exists first.
    
    // 2. Acquire Write Lock to create session
    std::unique_lock<std::shared_mutex> lock(resourceMutex);
    
    // Double check session cache
    if (sessionCache.count(key)) return sessionCache[key];

    // Check if module exists (Internal lookup)
    auto modIt = moduleCache.find(key);
    if (modIt == moduleCache.end()) {
        LOGE("Cannot create session, module not found: %s", key.c_str());
        return nullptr;
    }
    
    if (!engine) {
        LOGE("Engine is null during session creation.");
        return nullptr;
    }

    // 3. Create and Initialize Session
    // We create the object under the lock, but initialization might be heavy.
    // Since 'initialize()' registers functions and setups WASI, keeping it under lock is safer
    // to avoid race conditions on the same module instance if any.
    auto* session = new WasmSession(engine, modIt->second, key);
    
    // Note: session->initialize() is thread-safe internally, but calling it here 
    // ensures the session is fully ready before publishing it to the cache.
    if (!session->initialize()) {
        LOGE("Failed to initialize session: %s", key.c_str());
        delete session;
        return nullptr;
    }

    sessionCache[key] = session;
    LOGI("Session Created and Cached: %s", key.c_str());
    return session;
}

/**
 * Release a specific Session.
 *
 * 2025-12-02
 * @author crowforkotlin
 */
void WasmRuntime::releaseSession(const std::string& key) {
    std::unique_lock<std::shared_mutex> lock(resourceMutex);
    auto it = sessionCache.find(key);
    if (it != sessionCache.end()) {
        delete it->second; // WasmSession destructor handles resource cleanup
        sessionCache.erase(it);
        LOGI("Session Released: %s", key.c_str());
    }
}

/**
 * Release a Module and its Session.
 *
 * 2025-12-02
 * @author crowforkotlin
 */
void WasmRuntime::releaseModule(const std::string& key) {
    std::unique_lock<std::shared_mutex> lock(resourceMutex);
    
    // 1. Remove Session first
    auto sessIt = sessionCache.find(key);
    if (sessIt != sessionCache.end()) {
        delete sessIt->second;
        sessionCache.erase(sessIt);
    }

    // 2. Remove Module
    auto modIt = moduleCache.find(key);
    if (modIt != moduleCache.end()) {
        wasmtime_module_delete(modIt->second);
        moduleCache.erase(modIt);
        LOGI("Module Released: %s", key.c_str());
    }
}