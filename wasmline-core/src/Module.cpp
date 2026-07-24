/**
 * Module.native
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
    namespace {
        bool hasSuffixIgnoreCase(const std::string& value, const std::string& suffix) {
            if (value.size() < suffix.size()) return false;
            return std::equal(suffix.rbegin(), suffix.rend(), value.rbegin(), [](char lhs, char rhs) {
                return std::tolower(static_cast<unsigned char>(lhs)) == std::tolower(static_cast<unsigned char>(rhs));
            });
        }
    } // namespace

    // Singleton Accessor
    Module& Module::getInstance() {
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
     * Shared Logic: File Read + raw wasm compilation or precompiled artifact deserialization.
     * No locks are held inside this function.
     */
    wasmtime_module_t* Module::compileInternal(const std::string& key, const std::string& filePath) {
        // 1. Engine Check
        wasm_engine_t* engine = Engine::getInstance().getEngine();
        if (!engine) {
            LOGE("[Wasmtime] Module --> Engine not initialized.");
            return nullptr;
        }

        const bool rawWasm = hasSuffixIgnoreCase(filePath, ".wasm");

        // 2. Compile raw wasm or deserialize precompiled artifacts
        wasmtime_module_t* module = nullptr;
        wasmtime_error_t* error = nullptr;

        if (rawWasm) {
#ifdef WASMTIME_FEATURE_COMPILER
            std::vector<uint8_t> data = Utils::readFile(filePath);
            if (data.empty()) {
                LOGE("[Wasmtime] Module --> Failed to read file: %s", filePath.c_str());
                return nullptr;
            }

            LOGI("[Wasmtime] Module --> Compiling raw wasm module for %s", filePath.c_str());
            error = wasmtime_module_new(engine, reinterpret_cast<const uint8_t*>(data.data()), data.size(), &module);
#else
            LOGE("[Wasmtime] Module --> Raw wasm compilation not available (no compiler). Use precompiled .pwasm artifacts. file=%s",
                 filePath.c_str());
            return nullptr;
#endif
        } else {
            LOGI("[Wasmtime] Module --> Deserializing precompiled artifact for %s", filePath.c_str());
            error = wasmtime_module_deserialize_file(engine, filePath.c_str(), &module);
        }

        // 3. Error Handling
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

    // Load Module (Thread-Safe & Optimized)
    wasmtime_module_t* Module::load(const std::string& key, const std::string& filePath) {
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
        wasmtime_module_t* module = compileInternal(key, filePath);

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
    wasmtime_module_t* Module::loadUnsafe(const std::string& key, const std::string& filePath) {
        // 1. Direct Cache Check
        auto it = moduleCache.find(key);
        if (it != moduleCache.end()) {
            LOGI("[Wasmtime] Module (Unsafe) --> Get module from memory Cache: %s", key.c_str());
            return it->second;
        }

        // 2. Core Logic (Reuse)
        wasmtime_module_t* module = compileInternal(key, filePath);

        // 3. Update Cache
        if (module) {
            moduleCache[key] = module;
            LOGI("[Wasmtime] Module (Unsafe) --> Loaded and cached: %s", key.c_str());
        }

        return module;
    }

    // Get Cached Module
    wasmtime_module_t* Module::get(const std::string& key) {
        std::lock_guard<std::mutex> lock(cacheMutex);
        auto it = moduleCache.find(key);
        return (it != moduleCache.end()) ? it->second : nullptr;
    }

    // Release Specific Module
    void Module::release(const std::string& key) {
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
        for (auto& pair : moduleCache) {
            wasmtime_module_delete(pair.second);
        }
        moduleCache.clear();
        LOGI("[Wasmtime] Module --> All modules released.");
    }
} // namespace wasmline
