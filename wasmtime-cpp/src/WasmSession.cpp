#include "WasmSession.h"
#include "WasmExecutor.h"

// 构造函数接收 EnginePtr 和 ModulePtr
// 智能指针拷贝会导致引用计数 +1，确保 Session 存活期间它们不被销毁
WasmSession::WasmSession(WasmModule::EnginePtr e, WasmModule::ModulePtr m) 
    : engine(e), module(m) {
    
    // 只要 module 和 engine 智能指针有效，.get() 就是安全的
    if (engine && module) {
        linker = wasmtime_linker_new(engine.get());
        wasmtime_linker_define_wasi(linker);
        WasmExecutor::registerHostFunctions(linker);
    }
}

WasmSession::~WasmSession() {
    // 只负责 linker 的销毁
    if (linker) {
        wasmtime_linker_delete(linker);
        linker = nullptr;
    }
    // 析构函数结束时：
    // engine 智能指针销毁 (Ref -1)
    // module 智能指针销毁 (Ref -1)
}

std::string WasmSession::call(const std::string& action, const std::string& json) {
    if (!linker || !module || !engine) return "{\"error\": \"Session invalid\"}";
    
    // 传递原始指针给 Executor (Executor 生命周期短于 Session，所以安全)
    WasmExecutor exec(engine.get(), linker, module.get(), action, json);
    return exec.run();
}