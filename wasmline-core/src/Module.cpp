/**
 * Module.cpp
 * Implementation of Module.
 * Handles the complexities of file I/O, compilation, and thread-safe caching.
 *
 * 2025-12-03
 * @author crowforkotlin
 */

#include <extensions/FileUtils.h>
#include <extensions/Concurrency.h>
#include "Module.h"
#include "Engine.h"
#include "Logger.h"

namespace wasmline {
    // Singleton Accessor
    Module &Module::getInstance() {
        static Module instance;
        return instance;
    }

    // Destructor: Clean up all modules
    Module::~Module() {
        clear();
    }


    // ============================================================================
    // Private Helpers (Core Logic)
    // ============================================================================

    /**
     * Shared Logic: File Read + Compilation.
     * No locks are held inside this function.
     */
    wasmtime_module_t *Module::compileInternal(const std::string &key, const std::string &filePath, bool isJit) {
        // 1. IO Operation
        std::vector<uint8_t> data = Utils::readFile(filePath);
        if (data.empty()) {
            LOGE("[Wasmtime] Module --> Failed to read file: %s", filePath.c_str());
            return nullptr;
        }

        // 2. Engine Check
        wasm_engine_t *engine = Engine::getInstance().getEngine();
        if (!engine) {
            LOGE("[Wasmtime] Module --> Engine not initialized.");
            return nullptr;
        }

        // 3. Compilation / Deserialization
        wasmtime_module_t *module = nullptr;
        wasmtime_error_t *error = nullptr;

        if (isJit) {
            LOGI("[Wasmtime] Module --> Jit Compiling for %s...", filePath.c_str());
            error = wasmtime_module_new(engine, data.data(), data.size(), &module);
        } else {
            LOGI("[Wasmtime] Module --> Aot Deserializing for %s...", filePath.c_str());
            error = wasmtime_module_deserialize(engine, data.data(), data.size(), &module);
        }

        // 4. Error Handling
        if (error) {
            wasm_byte_vec_t msg;
            wasmtime_error_message(error, &msg);
            LOGE("[Wasmtime] Module --> Error loading module %s: %s", key.c_str(), msg.data);
            wasm_byte_vec_delete(&msg);
            wasmtime_error_delete(error);
            return nullptr;
        }

        return module;
    }

    /**
     * Shared Logic: Serialization + File Write.
     * No locks are held for file writing, but caller must ensure 'module' is valid.
     */
    bool Module::serializeInternal(const std::string &key, wasmtime_module_t *module, const std::string &outPath) {
        if (!module) return false;

        // 1. Serialize via Wasmtime
        wasm_byte_vec_t serialized;
        wasmtime_error_t *err = wasmtime_module_serialize(module, &serialized);

        if (err) {
            wasmtime_error_delete(err);
            LOGE("[Wasmtime] Module --> Serialization failed for %s", key.c_str());
            return false;
        }

        // 2. Write to File
        bool success = Utils::writeFile(outPath, reinterpret_cast<const uint8_t *>(serialized.data), serialized.size);
        wasm_byte_vec_delete(&serialized);

        if (success) {
            LOGI("[Wasmtime] Module --> Saved cache to %s", outPath.c_str());
        } else {
            LOGE("[Wasmtime] Module --> Failed to write cache file: %s", outPath.c_str());
        }

        return success;
    }


    // Load Module (Thread-Safe & Optimized)
    wasmtime_module_t *Module::load(const std::string &key, const std::string &filePath, bool isJit) {
        std::unique_lock<std::mutex> lock(cacheMutex);

        // 1. Check & Wait Phase
        while (true) {
            auto it = moduleCache.find(key);
            if (it != moduleCache.end()) {
                LOGI("[Wasmtime] Module --> Get module from memory Cache: %s", key.c_str());
                return it->second;
            }
            if (loadingSet.count(key)) {
                LOGI("[Wasmtime] Module --> Waiting for another thread to load: %s", key.c_str());
                cv.wait(lock);
            } else {
                loadingSet.insert(key);
                break;
            }
        }

        // 2. Prepare RAII Guard
        WasmScopeGuard guard(cacheMutex, loadingSet, cv, key);

        // 3. Unlock for heavy lifting
        lock.unlock();

        // 4. Core Logic (Reuse)
        wasmtime_module_t *module = compileInternal(key, filePath, isJit);

        // 5. Commit Phase
        lock.lock();

        if (module) {
            moduleCache[key] = module;
            LOGI("[Wasmtime] Module --> Successfully loaded and cached: %s", key.c_str());
        }

        // Cleanup state
        loadingSet.erase(key);
        cv.notify_all();
        guard.commit();
        lock.unlock();

        return module;
    }

    // Load Module (Unsafe / Fast)
    wasmtime_module_t *Module::loadUnsafe(const std::string &key, const std::string &filePath, bool isJit) {
        // 1. Direct Cache Check
        auto it = moduleCache.find(key);
        if (it != moduleCache.end()) {
            LOGI("[Wasmtime] Module (Unsafe) --> Get module from memory Cache: %s", key.c_str());
            return it->second;
        }

        // 2. Core Logic (Reuse)
        wasmtime_module_t *module = compileInternal(key, filePath, isJit);

        // 3. Update Cache
        if (module) {
            moduleCache[key] = module;
            LOGI("[Wasmtime] Module (Unsafe) --> Loaded and cached: %s", key.c_str());
        }

        return module;
    }

    // Serialize Module (Thread-Safe)
    bool Module::serialize(const std::string &key, const std::string &outPath) {
        std::lock_guard<std::mutex> lock(cacheMutex);

        auto it = moduleCache.find(key);
        if (it == moduleCache.end()) {
            LOGE("[Wasmtime] Module --> Cannot save cache, module not found: %s", key.c_str());
            return false;
        }

        // Call helper inside the lock to ensure module is not deleted by another thread
        return serializeInternal(key, it->second, outPath);
    }

    // Serialize Module (Unsafe / Fast)
    bool Module::serializeUnsafe(const std::string &key, const std::string &outPath) {
        // No lock held
        auto it = moduleCache.find(key);
        if (it == moduleCache.end()) {
            LOGE("[Wasmtime] Module (Unsafe) --> Cannot save cache, module not found: %s", key.c_str());
            return false;
        }

        // Call common serialize
        return serializeInternal(key, it->second, outPath);
    }

    // Get Cached Module
    wasmtime_module_t *Module::get(const std::string &key) {
        std::lock_guard<std::mutex> lock(cacheMutex);
        auto it = moduleCache.find(key);
        return (it != moduleCache.end()) ? it->second : nullptr;
    }

    // Release Specific Module
    void Module::release(const std::string &key) {
        std::lock_guard<std::mutex> lock(cacheMutex);
        auto it = moduleCache.find(key);
        if (it != moduleCache.end()) {
            wasmtime_module_delete(it->second);
            moduleCache.erase(it);
            LOGI("[Wasmtime] Module --> Released module key is : %s", key.c_str());
        }
    }

    // Clear All Modules
    void Module::clear() {
        std::lock_guard<std::mutex> lock(cacheMutex);
        for (auto &pair: moduleCache) {
            wasmtime_module_delete(pair.second);
        }
        moduleCache.clear();
        LOGI("[Wasmtime] Module --> All modules released.");
    }
}