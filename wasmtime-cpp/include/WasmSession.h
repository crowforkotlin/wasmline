#pragma once
#include <string>
#include <vector>
#include "wasm.h"
#include "wasmtime.h"

namespace crow {

    class WasmSession {
    public:
        WasmSession(wasm_engine_t* engine, wasmtime_module_t* module);
        ~WasmSession();

        void registerHostFunctions();
        std::string call(const std::string& action, const std::string& json);

        std::string inputAction;
        std::string inputJson;
        std::string outputResult;

    private:
        wasmtime_store_t* store = nullptr;
        wasmtime_context_t* context = nullptr;
        wasmtime_linker_t* linker = nullptr;
        wasmtime_module_t* module = nullptr;

        // Host Functions Callbacks
        static wasm_trap_t* host_get_action_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
        static wasm_trap_t* host_get_json_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
        static wasm_trap_t* host_read_input_byte(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
        static wasm_trap_t* host_write_result_byte(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
    };
}