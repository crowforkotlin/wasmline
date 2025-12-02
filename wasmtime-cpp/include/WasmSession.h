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
#include "wasmtime.h"

class WasmSession {
public:
    WasmSession(wasm_engine_t* engine, wasmtime_module_t* module, std::string key);
    ~WasmSession();

    // Initializes the session (Linker, WASI, Instance). Thread-safe.
    bool initialize();

    // Executes a specific action with binary input/output
    // json/protobuf are treated simply as byte arrays here
    std::string call(const char* action, size_t actionLen, const char* input, size_t inputLen);

public:
    // Temporary storage pointers for current execution context (accessed by Host Functions)
    const char* currentActionPtr = nullptr;
    size_t currentActionLen = 0;
    const char* currentInputPtr = nullptr;
    size_t currentInputLen = 0;
    std::string currentOutputBuffer;

private:
    std::string key;
    wasm_engine_t* engine;
    wasmtime_module_t* module;

    wasmtime_store_t* store = nullptr;
    wasmtime_context_t* context = nullptr;
    wasmtime_linker_t* linker = nullptr;
    wasmtime_instance_t instance;
    wasmtime_memory_t memory;

    bool isInitialized = false;
    bool hasMemory = false;
    std::mutex sessionMutex;

    void registerHostFunctions();
    
    // Static Host Callbacks
    static wasm_trap_t* host_get_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
    static wasm_trap_t* host_copy_to_memory(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
    static wasm_trap_t* host_read_from_memory(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
};