#pragma once
#include <string>
#include <unordered_map>
#include <mutex>
#include <shared_mutex>
#include "wasm.h"
#include "wasmtime.h"

namespace crow {

    class WasmManager {
    public:
        static WasmManager& getInstance();

        void initEngine();
        void releaseEngine();

        /**
         * 智能加载模块 (核心优化)
         * 逻辑：Check Cache -> (Miss) -> Read File -> Compile/Deserialize -> Cache
         * 
         * @param key 模块唯一标识
         * @param filePath 文件路径 (仅在缓存未命中时读取)
         * @param isJit true=源码编译(.wasm), false=AOT加载(.cwasm)
         */
        wasmtime_module_t* getOrLoadModule(const std::string& key, const std::string& filePath, bool isJit);

        // 获取已缓存的模块
        wasmtime_module_t* getModule(const std::string& key);

        // 释放模块
        void releaseModule(const std::string& key);

        wasm_engine_t* getEngine() const;

    private:
        WasmManager() = default;
        ~WasmManager();

        wasm_engine_t* engine = nullptr;
        std::unordered_map<std::string, wasmtime_module_t*> moduleCache;
        mutable std::shared_mutex cacheMutex; // 读写锁

        wasm_config_t* createConfig();
    };
}