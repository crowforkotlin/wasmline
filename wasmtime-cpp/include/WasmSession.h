/**
 * Manages a single Wasm instance execution environment.
 * Handles WASI configuration, memory mapping, and host function callbacks.
 *
 * Date: 2025-12-02
 * Author: crowforkotlin
 */

#pragma once
#include <string>
#include <mutex>
#include "WasmOutboundHandler.h"
#include "wasmtime.h"
#include "WasmOutboundHandler.h"
#include <functional>

class WasmSession {
public:
    WasmSession(wasm_engine_t *engine, wasmtime_module_t *module, std::string key);

    ~WasmSession();

    // Initializes the session (Linker, WASI, Instance). Thread-safe.
    bool initialize();

    // Inbound Call (Host -> Wasm)
    std::string invokeInbound(const char *action, size_t actionLen, const char *data, size_t dataLen);

    // 注入 Host 处理器
    void setOutboundHandler(std::unique_ptr<WasmOutboundHandler> handler);

private:

    // Inbound 通道状态 (由宿主填充，由 Wasm 读取)
    struct {
        const char* actionPtr = nullptr;
        size_t actionLen = 0;
        const char* dataPtr = nullptr;
        size_t dataLen = 0;
        std::string responseBuffer; // Wasm 处理完后的结果存这里
    } inbound;

    // Outbound 通道状态 (由 Wasm 填充，由宿主处理)
    struct {
        std::unique_ptr<WasmOutboundHandler> handler;
        std::string responseBuffer; // 宿主处理完后的结果存这里，等待 Wasm 拉取
    } outbound;

    std::string key;
    wasm_engine_t *engine;
    wasmtime_module_t *module;

    wasmtime_store_t *store = nullptr;
    wasmtime_context_t *context = nullptr;
    wasmtime_linker_t *linker = nullptr;
    wasmtime_instance_t instance;
    wasmtime_memory_t memory;

    bool isInitialized = false;
    bool hasMemory = false;
    std::mutex sessionMutex;

    void registerHostFunctions();

    // Static Host Callbacks
    static wasm_trap_t *bridge_inbound_get_size(void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs, wasmtime_val_t *results, size_t nresults);

    static wasm_trap_t *bridge_inbound_copy_params(void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs, wasmtime_val_t *results, size_t nresults);

    static wasm_trap_t *bridge_inbound_set_response(void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs, wasmtime_val_t *results, size_t nresults);

    // Outbound Host Functions
    static wasm_trap_t* bridge_outbound_call_host(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
    static wasm_trap_t* bridge_outbound_get_response(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
};