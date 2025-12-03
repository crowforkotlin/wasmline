/**
 * WasmModule.cpp
 * Implementation of WasmModule.
 * Handles the complexities of file I/O, compilation, and thread-safe caching.
 *
 * 2025-12-03
 * @author crowforkotlin
 */

#include "WasmModule.h"
#include "WasmEngine.h"
#include "WasmFileUtils.h" // Utils::readFile/writeFile
#include "WasmLogger.h"

// Singleton Accessor
WasmModule& WasmModule::getInstance() {
    static WasmModule instance;
    return instance;
}

// Destructor: Clean up all modules
WasmModule::~WasmModule() {
    clear();
}

// Load Module Logic
wasmtime_module_t* WasmModule::load(const std::string& key, const std::string& filePath, bool isJit) {
    // 1. Fast Path: Check cache with Read Lock
    {
        std::shared_lock<std::shared_mutex> lock(cacheMutex);
        auto it = moduleCache.find(key);
        if (it != moduleCache.end()) {
            return it->second;
        }
    }

    // 2. IO Operation: Read file content (No lock held to avoid blocking other threads)
    std::vector<uint8_t> data = Utils::readFile(filePath);
    if (data.empty()) {
        LOGE("[Wasmtime] WasmModule --> Failed to read file: %s", filePath.c_str());
        return nullptr;
    }

    // 3. Compilation/Deserialization
    // We need the engine to create a module
    wasm_engine_t* engine = WasmEngine::getInstance().getEngine();
    if (!engine) {
        LOGE("[Wasmtime] WasmModule --> Engine not initialized. Call WasmEngine::init() first.");
        return nullptr;
    }

    // 4. Slow Path: Acquire Write Lock to update cache
    std::unique_lock<std::shared_mutex> lock(cacheMutex);

    // Double-check: Another thread might have loaded it while we were reading/waiting
    if (moduleCache.count(key)) {
        return moduleCache[key];
    }

    wasmtime_module_t* module = nullptr;
    wasmtime_error_t* error = nullptr;

    if (isJit) {
        // Compile from Source (.wasm)
        LOGI("[Wasmtime] WasmModule --> Compiling source for %s...", key.c_str());
        error = wasmtime_module_new(engine, data.data(), data.size(), &module);
    } else {
        // Deserialize from Binary (.cwasm)
        LOGI("[Wasmtime] WasmModule --> Loading cache for %s...", key.c_str());
        error = wasmtime_module_deserialize(engine, data.data(), data.size(), &module);
    }

    if (error) {
        wasm_byte_vec_t msg;
        wasmtime_error_message(error, &msg);
        LOGE("[Wasmtime] WasmModule --> Error loading module %s: %s", key.c_str(), msg.data);
        wasm_byte_vec_delete(&msg);
        wasmtime_error_delete(error);
        return nullptr;
    }

    // Cache the successfully loaded module
    moduleCache[key] = module;
    LOGI("[Wasmtime] WasmModule --> Successfully loaded and cached: %s", key.c_str());
    return module;
}

// Get Cached Module
wasmtime_module_t* WasmModule::get(const std::string& key) {
    std::shared_lock<std::shared_mutex> lock(cacheMutex);
    auto it = moduleCache.find(key);
    return (it != moduleCache.end()) ? it->second : nullptr;
}

// Serialize Module to File
bool WasmModule::serialize(const std::string& key, const std::string& outPath) {
    // Acquire Read Lock because we need to read the module pointer
    std::shared_lock<std::shared_mutex> lock(cacheMutex);
    
    auto it = moduleCache.find(key);
    if (it == moduleCache.end()) {
        LOGE("[Wasmtime] WasmModule --> Cannot save cache, module not found: %s", key.c_str());
        return false;
    }

    wasm_byte_vec_t serialized;
    wasmtime_error_t* err = wasmtime_module_serialize(it->second, &serialized);

    if (err) {
        wasmtime_error_delete(err);
        LOGE("[Wasmtime] WasmModule --> Serialization failed for %s", key.c_str());
        return false;
    }

    // Write to file using Utils
    bool success = Utils::writeFile(outPath, reinterpret_cast<const uint8_t*>(serialized.data), serialized.size);
    wasm_byte_vec_delete(&serialized);
    
    if (success) LOGI("[Wasmtime] WasmModule --> Saved cache to %s", outPath.c_str());
    return success;
}

// Release Specific Module
void WasmModule::release(const std::string& key) {
    std::unique_lock<std::shared_mutex> lock(cacheMutex);
    auto it = moduleCache.find(key);
    if (it != moduleCache.end()) {
        wasmtime_module_delete(it->second);
        moduleCache.erase(it);
        LOGI("[Wasmtime] WasmModule --> Released module %s", key.c_str());
    }
}

// Clear All Modules
void WasmModule::clear() {
    std::unique_lock<std::shared_mutex> lock(cacheMutex);
    for (auto& pair : moduleCache) {
        wasmtime_module_delete(pair.second);
    }
    moduleCache.clear();
    LOGI("[Wasmtime] WasmModule --> All modules released.");
}