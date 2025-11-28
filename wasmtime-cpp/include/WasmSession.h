#ifndef WASM_SESSION_H
#define WASM_SESSION_H

#include "WasmCommon.h"
#include "WasmModule.h"

class WasmSession {
public:
    // 构造函数接收智能指针
    WasmSession(WasmModule::EnginePtr engine, WasmModule::ModulePtr module);
    ~WasmSession();

    std::string call(const std::string& action, const std::string& json);

    // Getters
    wasm_engine_t* getEngineRaw() const { return engine.get(); }
    wasmtime_module_t* getModuleRaw() const { return module.get(); }
    
    // 【修复点】添加此方法供 JNI 调用
    WasmModule::ModulePtr getModulePtr() const { return module; }

private:
    // 强引用：保证 Session 存活期间，Engine 和 Module 都不被销毁
    WasmModule::EnginePtr engine; 
    WasmModule::ModulePtr module; 
    
    wasmtime_linker_t* linker = nullptr;
};
#endif //WASM_SESSION_H