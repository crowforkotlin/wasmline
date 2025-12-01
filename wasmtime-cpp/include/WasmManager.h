#pragma once
#include <string>
#include <unordered_map>
#include <mutex>
#include <shared_mutex>
#include "wasm.h"
#include "wasmtime.h"
// 必须包含 Session 头文件以便管理指针
#include "WasmSession.h" 

namespace crow {

    class WasmManager {
    public:
        static WasmManager& getInstance();

        void initEngine();
        void releaseEngine();

        // 加载模块（保持不变）
        wasmtime_module_t* getOrLoadModule(const std::string& key, const std::string& filePath, bool isJit);
        wasmtime_module_t* getModule(const std::string& key);
        void releaseModule(const std::string& key);

        // [新增] 获取或创建缓存的 Session
        WasmSession* getOrCreateSession(const std::string& key);

        // [新增] 释放指定 Session (可选)
        void releaseSession(const std::string& key);

        wasm_engine_t* getEngine() const;

    private:
        WasmManager() = default;
        ~WasmManager();

        wasm_engine_t* engine = nullptr;
        std::unordered_map<std::string, wasmtime_module_t*> moduleCache;
        
        // [新增] Session 缓存：Key 与 ModuleKey 保持一致
        std::unordered_map<std::string, WasmSession*> sessionCache;

        mutable std::shared_mutex cacheMutex; // 保护 moduleCache
        mutable std::shared_mutex sessionMutex; // 保护 sessionCache

        wasm_config_t* createConfig();
    };
}