#include "WasmManager.h"
#include "WasmLog.h"
#include "FileUtils.h" // 必须引用文件工具
#include "WasmTimer.h" // 引用计时器
#include <vector>

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

    // 核心优化：懒加载 + 线程安全
    wasmtime_module_t* WasmManager::getOrLoadModule(const std::string& key, const std::string& filePath, bool isJit) {
        // [阶段1] 快速路径：读锁检查 (无 IO)
        {
            std::shared_lock<std::shared_mutex> lock(cacheMutex);
            if (!engine) {
                LOGE("Engine not initialized!");
                return nullptr;
            }
            auto it = moduleCache.find(key);
            if (it != moduleCache.end()) {
                // LOGI("Cache Hit: %s", key.c_str()); 
                return it->second; // 缓存命中，直接返回，耗时极低
            }
        }

        // [阶段2] 慢速路径：写锁加载 (IO + 编译)
        std::unique_lock<std::shared_mutex> lock(cacheMutex);
        
        if (!engine) return nullptr;

        // Double Check: 防止在等待锁的过程中，其他线程已经加载了
        if (moduleCache.find(key) != moduleCache.end()) {
            return moduleCache[key];
        }

        LOGI("Cache Miss. Loading from disk: %s", filePath.c_str());
        
        // 读取文件 (耗时操作)
        auto data = FileUtils::readFile(filePath);
        if (data.empty()) {
            LOGE("File read failed or empty: %s", filePath.c_str());
            return nullptr;
        }

        wasmtime_module_t* module = nullptr;
        wasmtime_error_t* error = nullptr;

        // 编译或反序列化 (耗时操作)
        if (isJit) {
            error = wasmtime_module_new(engine, data.data(), data.size(), &module);
        } else {
            error = wasmtime_module_deserialize(engine, data.data(), data.size(), &module);
        }

        // 释放文件内存，data在此处析构，节省内存
        // (module 已经创建在 engine 内部了)
        
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
            // Wasmtime 内部引用计数机制：
            // 即使这里调用了 delete，如果还有 Instance (Session) 正在使用它，
            // 真正的内存释放会推迟到 Instance 销毁后。
            // 所以这里直接 delete 是安全的，Manager 不再持有它即可。
            wasmtime_module_delete(it->second);
            moduleCache.erase(it);
            LOGI("Module Released: %s", key.c_str());
        }
    }

    wasm_engine_t* WasmManager::getEngine() const {
        return engine;
    }
}