#ifndef WASM_MODULE_H
#define WASM_MODULE_H

#include "WasmCommon.h"

class WasmModule {
public:
    // 定义智能指针类型
    using EnginePtr = std::shared_ptr<wasm_engine_t>;
    using ModulePtr = std::shared_ptr<wasmtime_module_t>;

    // 获取全局 Engine
    static EnginePtr getGlobalEngine();

    // 加载方法 (返回智能指针)
    static ModulePtr loadFromPath(const std::string& path);
    static ModulePtr loadFromSourcePath(const std::string& path);
    static ModulePtr loadFromSource(const std::vector<uint8_t>& source);

    // 资源清理
    static void removeCache(const std::string& path);
    static void freeAllResources();

    // 序列化 (接收智能指针)
    static bool saveCacheToPath(ModulePtr module, const std::string& path);
};
#endif //WASM_MODULE_H