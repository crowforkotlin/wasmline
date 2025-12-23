#pragma once
#include <string>
#include "wasmtime.h"

class WasmSession;

class WasmInboundChannel {
public:
    explicit WasmInboundChannel(WasmSession* session);

    /**
     * Inbound 主入口: Host 调用 Wasm
     * @param action Wasm 导出的函数名
     * @param data 传入的序列化数据
     */
    std::string call(const std::string& action, const std::string& data);

private:
    WasmSession* session;
}