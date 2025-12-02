/**
 * Implementation of WasmRuntime.
 * Handles Android-specific Wasmtime configuration.
 *
 * Date: 2025-12-02
 * Author: crowforkotlin
 */

#include "WasmRuntime.h"
#include "WasmLogger.h"
#include "WasmFileUtils.h"

WasmRuntime& WasmRuntime::getInstance() {
    static WasmRuntime instance;
    return instance;
}

WasmRuntime::~WasmRuntime() {
    releaseEngine();
}

wasm_config_t* WasmRuntime::createConfig() {
    wasm_config_t* conf = wasm_config_new();
    
    // Kotlin/Wasm requirements
    wasmtime_config_wasm_gc_set(conf, true);
    wasmtime_config_wasm_function_references_set(conf, true);
    wasmtime_config_wasm_exceptions_set(conf, true);
    
    // Android Optimization & Safety
    wasmtime_config_wasm_simd_set(conf, false);
    wasmtime_config_wasm_relaxed_simd_set(conf, false);
    
    // CRITICAL for Android: Disable signal handlers to avoid conflicts with ART
    wasmtime_config_signals_based_traps_set(conf, false);
    
    // CRITICAL for Memory: Set guard pages to 0 to prevent VSS OOM
    wasmtime_config_memory_guard_size_set(conf, 0);
    
    wasmtime_config_max_wasm_stack_set(conf, 512 * 1024); // 512KB stack
    
    // Compilation Optimization
    wasmtime_config_cranelift_opt_level_set(conf, WASMTIME_OPT_LEVEL_SPEED_AND_SIZE);
    wasmtime_config_cranelift_debug_verifier_set(conf, false);

    return conf;
}

void WasmRuntime::initEngine() {
    std::unique_lock<std::shared_mutex> lock(resourceMutex);
    if (!engine) {
        auto conf = createConfig();
        engine = wasm_engine_new_with_config(conf);
        LOGI("Wasm Engine Initialized.");
    }
}

void WasmRuntime::releaseEngine() {
    std::unique_lock<std::shared_mutex> lock(resourceMutex);
    
    for (auto& kv : sessionCache) delete kv.second;
    sessionCache.clear();

    for (auto& kv : moduleCache) wasmtime_module_delete(kv.second);
    moduleCache.clear();

    if (engine) {
        wasm_engine_delete(engine);
        engine = nullptr;
        LOGI("Wasm Engine Released.");
    }
}

wasmtime_module_t* WasmRuntime::loadModule(const std::string& key, const std::string& filePath, bool isJit) {
    // Check cache first (Read Lock)
    {
        std::shared_lock<std::shared_mutex> lock(resourceMutex);
        if (!engine) return nullptr;
        if (moduleCache.count(key)) return moduleCache[key];
    }

    // Load data
    auto data = Utils::readFile(filePath);
    if (data.empty()) {
        LOGE("[wasmtime] Failed to read module file: %s", filePath.c_str());
        return nullptr;
    } else {
        LOGI("[wasmtime]  Success Read module file: %s", filePath.c_str());
    }

    // Compile/Deserialize (Write Lock)
    std::unique_lock<std::shared_mutex> lock(resourceMutex);
    if (!engine) return nullptr;
    if (moduleCache.count(key)) return moduleCache[key]; // Double check

    wasmtime_module_t* module = nullptr;
    wasmtime_error_t* error = nullptr;

    if (isJit) {
        // 源码编译 (.wasm)
        error = wasmtime_module_new(engine, data.data(), data.size(), &module);
    } else {
        // 缓存加载 (.cwasm)
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
    LOGI("Module Loaded: %s", key.c_str());
    return module;
}

wasmtime_module_t* WasmRuntime::getModule(const std::string& key) {
    std::shared_lock<std::shared_mutex> lock(resourceMutex);
    auto it = moduleCache.find(key);
    return (it != moduleCache.end()) ? it->second : nullptr;
}

WasmSession* WasmRuntime::getSession(const std::string& key) {
    // 1. Try get existing session (Read Lock)
    {
        std::shared_lock<std::shared_mutex> lock(resourceMutex);
        auto it = sessionCache.find(key);
        if (it != sessionCache.end()) return it->second;
    }

    // 2. Find Module
    wasmtime_module_t* module = getModule(key); // Has its own lock
    if (!module) {
        LOGE("Cannot create session, module not found: %s", key.c_str());
        return nullptr;
    }

    // 3. Create Session (Write Lock)
    std::unique_lock<std::shared_mutex> lock(resourceMutex);
    if (sessionCache.count(key)) return sessionCache[key]; // Double check

    auto* session = new WasmSession(engine, module, key);
    if (!session->initialize()) {
        delete session;
        return nullptr;
    }

    sessionCache[key] = session;
    return session;
}

void WasmRuntime::releaseSession(const std::string& key) {
    std::unique_lock<std::shared_mutex> lock(resourceMutex);
    auto it = sessionCache.find(key);
    if (it != sessionCache.end()) {
        delete it->second;
        sessionCache.erase(it);
        LOGI("Session Released: %s", key.c_str());
    }
}

void WasmRuntime::releaseModule(const std::string& key) {
    // Must release session first as it depends on module
    releaseSession(key);

    std::unique_lock<std::shared_mutex> lock(resourceMutex);
    auto it = moduleCache.find(key);
    if (it != moduleCache.end()) {
        wasmtime_module_delete(it->second);
        moduleCache.erase(it);
        LOGI("Module Released: %s", key.c_str());
    }
}