#ifndef WASM_MODULE_H
#define WASM_MODULE_H

#include "WasmCommon.h"

class WasmModule {
public:
    ~WasmModule();

    // --- 工厂方法 ---
    // AOT: 从文件路径加载 (.cwasm)
    static WasmModule* loadFromPath(const std::string& path);
    // JIT: 从内存字节编译 (.wasm)
    static WasmModule* loadFromSource(const std::vector<uint8_t>& source);
    // 相比 loadFromSource(vector)，这个方法由 C++ 自己读文件，避免 Java 层 OOM
    static WasmModule* loadFromSourcePath(const std::string& path);
    
    // --- 功能 ---
    // 序列化当前模块并保存到指定路径
    bool saveCacheToPath(const std::string& path);

    // 执行调用
    std::string call(const std::string& action, const std::string& json);

    // 获取器
    wasm_engine_t* getEngine() const { return engine; }
    wasmtime_module_t* getModule() const { return module; }
    wasmtime_linker_t* getLinker() const { return linker; }

private:
    WasmModule();
    bool initCommon(); // 初始化 Engine 和 Linker

    wasm_engine_t* engine = nullptr;
    wasmtime_module_t* module = nullptr;
    wasmtime_linker_t* linker = nullptr;
};

#endif //WASM_MODULE_H