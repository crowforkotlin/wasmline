#include "WasmManager.h"
#include "WasmLog.h"
#include "FileUtils.h"

namespace crow {

    // ... (getInstance, createConfig 等保持不变) ...
    WasmManager& WasmManager::getInstance() {
        static WasmManager instance;
        return instance;
    }
    
    WasmManager::~WasmManager() { releaseEngine(); }

    wasm_config_t* WasmManager::createConfig() {
        wasm_config_t* conf = wasm_config_new();
        wasmtime_config_wasm_gc_set(conf, true);
        wasmtime_config_wasm_function_references_set(conf, true);
        wasmtime_config_wasm_exceptions_set(conf, true);
        wasmtime_config_wasm_simd_set(conf, false);
        wasmtime_config_wasm_relaxed_simd_set(conf, false);
        wasmtime_config_signals_based_traps_set(conf, false);
        wasmtime_config_memory_guard_size_set(conf, 0);
        wasmtime_config_max_wasm_stack_set(conf, 512 * 1024);
        return conf;
    }

    void WasmManager::initEngine() {
        std::unique_lock<std::shared_mutex> lock(cacheMutex);
        if (!engine) {
            auto conf = createConfig();
            engine = wasm_engine_new_with_config(conf);
            LOGI("Wasm Engine Initialized.");
        }
    }

    void WasmManager::releaseEngine() {
        // 1. 先清空所有 Session
        {
            std::unique_lock<std::shared_mutex> lock(sessionMutex);
            for (auto& kv : sessionCache) {
                delete kv.second; // 触发 Session 析构
            }
            sessionCache.clear();
        }

        // 2. 再清空 Modules 和 Engine
        std::unique_lock<std::shared_mutex> lock(cacheMutex);
        for (auto& kv : moduleCache) {
            wasmtime_module_delete(kv.second);
        }
        moduleCache.clear();
        if (engine) {
            wasm_engine_delete(engine);
            engine = nullptr;
            LOGI("Wasm Engine Released.");
        }
    }

    wasmtime_module_t* WasmManager::getOrLoadModule(const std::string& key, const std::string& filePath, bool isJit) {
        {
            std::shared_lock<std::shared_mutex> lock(cacheMutex);
            if (!engine) return nullptr;
            auto it = moduleCache.find(key);
            if (it != moduleCache.end()) return it->second;
        }

        std::unique_lock<std::shared_mutex> lock(cacheMutex);
        if (!engine) return nullptr;
        if (moduleCache.find(key) != moduleCache.end()) return moduleCache[key];

        LOGI("Cache Miss. Loading: %s", filePath.c_str());
        auto data = FileUtils::readFile(filePath);
        if (data.empty()) return nullptr;

        wasmtime_module_t* module = nullptr;
        wasmtime_error_t* error = nullptr;
        if (isJit) {
            error = wasmtime_module_new(engine, data.data(), data.size(), &module);
        } else {
            error = wasmtime_module_deserialize(engine, data.data(), data.size(), &module);
        }

        if (error) {
            wasmtime_error_delete(error);
            return nullptr;
        }
        moduleCache[key] = module;
        return module;
    }

    wasmtime_module_t* WasmManager::getModule(const std::string& key) {
        std::shared_lock<std::shared_mutex> lock(cacheMutex);
        auto it = moduleCache.find(key);
        return (it != moduleCache.end()) ? it->second : nullptr;
    }

    // [新增] 获取 Session，如果没有则创建
    WasmSession* WasmManager::getOrCreateSession(const std::string& key) {
        // 1. 尝试从缓存获取 (读锁)
        {
            std::shared_lock<std::shared_mutex> lock(sessionMutex);
            auto it = sessionCache.find(key);
            if (it != sessionCache.end()) {
                WasmSession* value = it->second;
                if (value != nullptr) {
                    LOGI("[wasmtime] 2. get session (Ptr): Found object at address: %p", (void*)value);
                } else {
                    LOGI("[wasmtime] 2. get session (Ptr): Found nullptr in cache for key!");
                }
                return value;
            }
        }

        // 2. 获取对应的 Module (需要先加载好)
        auto* module = getModule(key);
        if (!module) {
            LOGE("Cannot create session: Module %s not found", key.c_str());
            return nullptr;
        } else {
            LOGI("[wasmtime] 1. GET module cache (Ptr): address: %p", (void*)module);
        }

        // 3. 创建新 Session (写锁)
        std::unique_lock<std::shared_mutex> lock(sessionMutex);
        // 双重检查
        if (sessionCache.find(key) != sessionCache.end()) {
            LOGI("[wasmtime] 3. Create sessions and cache.");
            return sessionCache[key];
        }

        // 创建堆对象
        auto* session = new WasmSession(engine, module, key);
        // 初始化 (在此处初始化，确保只做一次)
        if (!session->initialize()) {
            delete session;
            return nullptr;
        }

        sessionCache[key] = session;
        return session;
    }

    // [新增] 释放 Session
    void WasmManager::releaseSession(const std::string& key) {
        std::unique_lock<std::shared_mutex> lock(sessionMutex);
        auto it = sessionCache.find(key);
        if (it != sessionCache.end()) {
            delete it->second; // 析构 WasmSession
            sessionCache.erase(it);
            LOGI("Session Released: %s", key.c_str());
        }
    }

    void WasmManager::releaseModule(const std::string& key) {
        // [关键] 释放模块前，必须先释放依赖该模块的 Session
        releaseSession(key);

        std::unique_lock<std::shared_mutex> lock(cacheMutex);
        auto it = moduleCache.find(key);
        if (it != moduleCache.end()) {
            wasmtime_module_delete(it->second);
            moduleCache.erase(it);
            LOGI("Module Released: %s", key.c_str());
        }
    }

    wasm_engine_t* WasmManager::getEngine() const { return engine; }
}