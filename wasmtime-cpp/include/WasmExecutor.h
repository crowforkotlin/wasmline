#ifndef WASM_EXECUTOR_H
#define WASM_EXECUTOR_H
#include "WasmCommon.h"

// 前置声明，避免循环引用
class WasmModule;

class WasmExecutor {
public:
    WasmExecutor(WasmModule* module, std::string action, std::string json);
    ~WasmExecutor();

    std::string run();

    // 注册 Host Functions 到 Linker
    static void registerHostFunctions(wasmtime_linker_t* linker);

    // --- 数据缓冲区 (Host Function 需访问) ---
    std::string inputAction;
    std::string inputJson;
    std::string outputResult;

private:
    WasmModule* holder;
    wasmtime_store_t* store = nullptr;
    wasmtime_context_t* context = nullptr;

    // --- Host Functions 回调 (Static) ---
    static wasm_trap_t* host_get_action_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
    static wasm_trap_t* host_get_json_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
    static wasm_trap_t* host_read_input_byte(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
    static wasm_trap_t* host_write_result_byte(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
};

#endif //WASM_EXECUTOR_H