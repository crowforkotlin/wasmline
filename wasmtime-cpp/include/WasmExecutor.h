#ifndef WASM_EXECUTOR_H
#define WASM_EXECUTOR_H
#include "WasmCommon.h"

class WasmExecutor {
public:
    WasmExecutor(wasm_engine_t* engine, wasmtime_linker_t* linker, wasmtime_module_t* module,
                 std::string action, std::string json);
    ~WasmExecutor();

    std::string run();

    static void registerHostFunctions(wasmtime_linker_t* linker);

    std::string inputAction;
    std::string inputJson;
    std::string outputResult;

private:
    wasm_engine_t* engine;
    wasmtime_linker_t* linker;
    wasmtime_module_t* module;

    wasmtime_store_t* store = nullptr;
    wasmtime_context_t* context = nullptr;

    static wasm_trap_t* host_get_action_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
    static wasm_trap_t* host_get_json_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
    static wasm_trap_t* host_read_input_byte(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
    static wasm_trap_t* host_write_result_byte(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
};
#endif //WASM_EXECUTOR_H