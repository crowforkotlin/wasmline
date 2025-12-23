#pragma once
#include "wasmtime.h"
#include "WasmOutboundHandler.h"
#include <memory>
#include <string>

class WasmSession; // 前置声明

class WasmOutboundHandler {
public:
    explicit WasmOutboundHandler(WasmSession* session);

    // 将本通道内的所有宿主函数注册到 Linker
    bool registerToLinker(wasmtime_linker_t* linker);

    // 设置最终处理业务逻辑的 Handler (如 Android JNI 侧)
    void setHandler(std::unique_ptr<WasmOutboundHandler> handler);

    // 提供给 Wasm 调用的内部逻辑：暂存结果
    void setLastInvokeResult(const std::string& result);
    const std::string& getLastInvokeResult() const;

private:
    WasmSession* session;
    std::unique_ptr<WasmOutboundHandler> hostHandler;
    std::string currentInvokeResult; // 存储 Host 执行后的结果，供 Wasm 随后读取

    // 静态回调函数 (符合 Wasmtime C API)
    static wasm_trap_t* proc_outbound_invoke(void* env, wasmtime_caller_t* caller, 
                                            const wasmtime_val_t* args, size_t nargs, 
                                            wasmtime_val_t* results, size_t nresults);
};