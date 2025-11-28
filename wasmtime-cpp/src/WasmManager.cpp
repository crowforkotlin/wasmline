#include "WasmManager.h"
#include "WasmLog.h"
#include <vector> // [修复] 必须显式包含 vector

namespace crow {

    WasmManager& WasmManager::getInstance() {
        static WasmManager instance;
        return instance;
    }

    WasmManager::~WasmManager() {
        releaseEngine();
    }

    wasm_config_t* WasmManager::createConfig() {
        wasm_config_t* conf = wasm_config_new();

        // 1. 开启高级特性
        wasmtime_config_wasm_gc_set(conf, true);
        wasmtime_config_wasm_function_references_set(conf, true);
        wasmtime_config_wasm_exceptions_set(conf, true);

        // 2. Android 兼容性配置 (至关重要)
        // 关闭 SIMD: 防止指令集不兼容
        wasmtime_config_wasm_simd_set(conf, false);
        wasmtime_config_wasm_relaxed_simd_set(conf, false);

        // 关闭信号 Trap: 这一步决定了必须在本地编译，不能用编译的文件
        wasmtime_config_signals_based_traps_set(conf, false);

        // 内存页保护设为 0: 适配 Android 虚拟内存机制
        wasmtime_config_memory_guard_size_set(conf, 0);

        // 限制栈大小 (512KB)
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
        std::unique_lock<std::shared_mutex> lock(cacheMutex);
        // 先释放所有 Module
        for (auto& kv : moduleCache) {
            // [修复] 这里 kv.second 现在是 wasmtime_module_t*，类型匹配了
            wasmtime_module_delete(kv.second);
        }
        moduleCache.clear();

        if (engine) {
            wasm_engine_delete(engine);
            engine = nullptr;
            LOGI("Wasm Engine Released.");
        }
    }

    wasmtime_module_t* WasmManager::loadModule(const std::string& key, const std::vector<uint8_t>& data, bool isJit) {
        // 1. 尝试从缓存获取 (读锁)
        {
            std::shared_lock<std::shared_mutex> lock(cacheMutex);
            if (!engine) {
                LOGE("Engine not initialized!");
                return nullptr;
            }
            auto it = moduleCache.find(key);
            if (it != moduleCache.end()) {
                return it->second;
            }
        }

        // 2. 缓存未命中，进行编译/加载 (写锁)
        std::unique_lock<std::shared_mutex> lock(cacheMutex);
        if (!engine) return nullptr;

        // Double Check
        if (moduleCache.find(key) != moduleCache.end()) {
            return moduleCache[key];
        }

        LOGI("Loading Module: %s (Size: %zu)", key.c_str(), data.size());

        wasmtime_module_t* module = nullptr;
        wasmtime_error_t* error = nullptr;

        if (isJit) {
            error = wasmtime_module_new(engine, data.data(), data.size(), &module);
        } else {
            error = wasmtime_module_deserialize(engine, data.data(), data.size(), &module);
        }

        if (error) {
            wasm_byte_vec_t msg;
            wasmtime_error_message(error, &msg);
            LOGE("Load Module Failed: %s", msg.data);
            wasm_byte_vec_delete(&msg);
            wasmtime_error_delete(error);
            return nullptr;
        }

        // 存入缓存
        moduleCache[key] = module;
        return module;
    }

    wasmtime_module_t* WasmManager::getModule(const std::string& key) {
        std::shared_lock<std::shared_mutex> lock(cacheMutex);
        auto it = moduleCache.find(key);
        if (it != moduleCache.end()) {
            return it->second;
        }
        return nullptr;
    }

    void WasmManager::releaseModule(const std::string& key) {
        std::unique_lock<std::shared_mutex> lock(cacheMutex);
        auto it = moduleCache.find(key);
        if (it != moduleCache.end()) {
            wasmtime_module_delete(it->second);
            moduleCache.erase(it);
            LOGI("Module Released: %s", key.c_str());
        }
    }

    wasm_engine_t* WasmManager::getEngine() const {
        return engine;
    }
}