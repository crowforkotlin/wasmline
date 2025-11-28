#pragma once
#include <string>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <shared_mutex>
#include "wasm.h"
#include "wasmtime.h"

namespace crow {

    /**
     * WasmManager (单例)
     * 职责：
     * 1. 管理全局 wasm_engine_t (线程安全)
     * 2. 缓存已加载的 wasmtime_module_t (避免重复编译)
     * 3. 负责资源的分配与回收
     */
    class WasmManager {
    public:
        static WasmManager& getInstance();

        // 初始化全局 Engine
        void initEngine();

        // 销毁全局 Engine (及所有模块)
        void releaseEngine();

        // 加载模块 (支持缓存复用)
        // key: 通常是文件路径，用于标识唯一性
        // data: 源码或字节码 (仅在首次加载时使用)
        // isJit: true为源码编译，false为AOT反序列化
        wasmtime_module_t* loadModule(const std::string& key, const std::vector<uint8_t>& data, bool isJit);

        // 获取已缓存的模块 (增加引用计数，防止被删)
        wasmtime_module_t* getModule(const std::string& key);

        // 卸载指定模块
        void releaseModule(const std::string& key);

        // 获取 Engine 指针
        wasm_engine_t* getEngine() const;

    private:
        WasmManager() = default;
        ~WasmManager();

        wasm_engine_t* engine = nullptr;

        // 模块缓存表: Key -> Module指针 (注意这里使用 wasmtime_module_t)
        std::unordered_map<std::string, wasmtime_module_t*> moduleCache;

        // 读写锁：保证多线程并发读取模块安全，同时写入(加载/卸载)时互斥
        mutable std::shared_mutex cacheMutex;

        // 创建 Android 优化的 Config
        wasm_config_t* createConfig();
    };
}