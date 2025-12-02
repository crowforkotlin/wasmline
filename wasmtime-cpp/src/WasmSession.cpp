/**
 * Implementation of WasmSession.
 *
 * Date: 2025-12-02
 * Author: crowforkotlin
 */

#include "WasmSession.h"
#include "WasmLogger.h"
#include <cstring>
#include <vector>

// Log callback for WASI
static ptrdiff_t wasi_log_writer(void* data, const unsigned char* buffer, size_t size) {
    if (size > 0 && buffer) {
        std::string msg(reinterpret_cast<const char*>(buffer), size > 1024 ? 1024 : size);
        LOGI("[WASI] %s", msg.c_str());
    }
    return size;
}

WasmSession::WasmSession(wasm_engine_t* eng, wasmtime_module_t* mod, std::string k)
    : engine(eng), module(mod), key(std::move(k)) {
    store = wasmtime_store_new(engine, this, nullptr);
    context = wasmtime_store_context(store);
    linker = wasmtime_linker_new(engine);
}

WasmSession::~WasmSession() {
    if (linker) wasmtime_linker_delete(linker);
    if (store) wasmtime_store_delete(store);
    LOGI("Session Destroyed: %s", key.c_str());
}

bool WasmSession::initialize() {
    std::lock_guard<std::mutex> lock(sessionMutex);
    if (isInitialized) return true;

    // 1. Setup WASI
    wasmtime_linker_define_wasi(linker);
    wasi_config_t* wasi = wasi_config_new();
    wasi_config_inherit_env(wasi);
    wasi_config_set_stdout_custom(wasi, wasi_log_writer, nullptr, nullptr);
    wasi_config_set_stderr_custom(wasi, wasi_log_writer, nullptr, nullptr);
    wasmtime_context_set_wasi(context, wasi);

    // 2. Register Host Functions
    registerHostFunctions();

    // 3. Instantiate Module
    wasm_trap_t* trap = nullptr;
    wasmtime_error_t* error = wasmtime_linker_instantiate(linker, context, module, &instance, &trap);
    if (error || trap) {
        if (error) {
            wasm_byte_vec_t msg;
            wasmtime_error_message(error, &msg);
            LOGE("Instantiation Error: %s", msg.data);
            wasm_byte_vec_delete(&msg);
            wasmtime_error_delete(error);
        }
        if (trap) {
            wasm_byte_vec_t msg;
            wasm_trap_message(trap, &msg);
            LOGE("Instantiation Trap: %s", msg.data);
            wasm_byte_vec_delete(&msg);
            wasm_trap_delete(trap);
        }
        return false;
    }

    // 4. Export Memory
    wasmtime_extern_t mem_ext;
    if (wasmtime_instance_export_get(context, &instance, "memory", 6, &mem_ext) &&
        mem_ext.kind == WASMTIME_EXTERN_MEMORY) {
        memory = mem_ext.of.memory;
        hasMemory = true;
    } else {
        LOGE("Failed to export 'memory'. Copy functions will fail.");
        hasMemory = false;
        return false;
    }

    // 5. Run _initialize (WASI Reactor model)
    wasmtime_extern_t init_func;
    if (wasmtime_instance_export_get(context, &instance, "_initialize", 11, &init_func)) {
        wasmtime_func_call(context, &init_func.of.func, nullptr, 0, nullptr, 0, &trap);
        if (trap) wasm_trap_delete(trap);
    }

    isInitialized = true;
    LOGI("Session Initialized: %s", key.c_str());
    return true;
}

std::string WasmSession::call(const char* action, size_t actionLen, const char* input, size_t inputLen) {
    std::lock_guard<std::mutex> lock(sessionMutex);
    if (!isInitialized) return "";

    // Set context pointers for Host Functions
    currentActionPtr = action;
    currentActionLen = actionLen;
    currentInputPtr = input;
    currentInputLen = inputLen;
    currentOutputBuffer.clear();

    wasmtime_extern_t run_entry;
    wasm_trap_t* trap = nullptr;

    // Call "run_entry" exported by Kotlin/Wasm
    if (wasmtime_instance_export_get(context, &instance, "run_entry", 9, &run_entry)) {
        wasmtime_error_t* error = wasmtime_func_call(context, &run_entry.of.func, nullptr, 0, nullptr, 0, &trap);
        
        if (error || trap) {
            if (trap) {
                wasm_byte_vec_t msg;
                wasm_trap_message(trap, &msg);
                LOGE("Runtime Trap: %s", msg.data);
                wasm_byte_vec_delete(&msg);
                wasm_trap_delete(trap);
            }
            if (error) wasmtime_error_delete(error);
            
            // Clear pointers on error
            currentActionPtr = nullptr;
            currentInputPtr = nullptr;
            return "";
        }
    } else {
        LOGE("Export 'run_entry' not found.");
    }

    // Reset pointers
    currentActionPtr = nullptr;
    currentInputPtr = nullptr;

    return currentOutputBuffer;
}

void WasmSession::registerHostFunctions() {
    auto define = [&](const char* name, wasmtime_func_callback_t cb, 
                      const std::vector<wasm_valkind_t>& params, 
                      const std::vector<wasm_valkind_t>& results) {
        wasm_valtype_vec_t p_vec, r_vec;
        std::vector<wasm_valtype_t*> p_types, r_types;
        for (auto k : params) p_types.push_back(wasm_valtype_new(k));
        for (auto k : results) r_types.push_back(wasm_valtype_new(k));
        wasm_valtype_vec_new(&p_vec, p_types.size(), p_types.data());
        wasm_valtype_vec_new(&r_vec, r_types.size(), r_types.data());
        wasm_functype_t* ty = wasm_functype_new(&p_vec, &r_vec);
        wasmtime_linker_define_func(linker, "env", 3, name, strlen(name), ty, cb, nullptr, nullptr);
        wasm_functype_delete(ty);
    };

    // Optimization: Merged size getters and copy functions
    // type: 0 = Action, 1 = Input/Json
    define("host_get_size", host_get_size, {WASM_I32}, {WASM_I32}); 
    define("host_copy_to_memory", host_copy_to_memory, {WASM_I32, WASM_I32, WASM_I32}, {});
    define("host_read_from_memory", host_read_from_memory, {WASM_I32, WASM_I32}, {});
}

// Helper to get session instance from caller
static WasmSession* get_session(wasmtime_caller_t* caller) {
    return reinterpret_cast<WasmSession*>(wasmtime_context_get_data(wasmtime_caller_context(caller)));
}

wasm_trap_t* WasmSession::host_get_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
    auto* self = get_session(caller);
    int32_t type = args[0].of.i32; // 0: Action, 1: Input
    results[0].kind = WASMTIME_I32;
    results[0].of.i32 = (int32_t)(type == 0 ? self->currentActionLen : self->currentInputLen);
    return nullptr;
}

wasm_trap_t* WasmSession::host_copy_to_memory(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
    auto* self = get_session(caller);
    if (!self->hasMemory) return wasmtime_trap_new("Memory not available", 20);

    int32_t type = args[0].of.i32;    // Source type
    int32_t wasmPtr = args[1].of.i32; // Destination in Wasm Memory
    int32_t len = args[2].of.i32;     // Length to copy

    const char* srcData = (type == 0) ? self->currentActionPtr : self->currentInputPtr;
    size_t maxLen = (type == 0) ? self->currentActionLen : self->currentInputLen;

    if (len < 0 || static_cast<size_t>(len) > maxLen) len = (int32_t)maxLen;
    
    uint8_t* rawMemory = wasmtime_memory_data(self->context, &self->memory);
    if (rawMemory && srcData && len > 0) {
        memcpy(rawMemory + wasmPtr, srcData, len);
    }
    return nullptr;
}

wasm_trap_t* WasmSession::host_read_from_memory(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
    auto* self = get_session(caller);
    if (!self->hasMemory) return wasmtime_trap_new("Memory not available", 20);

    int32_t wasmPtr = args[0].of.i32;
    int32_t len = args[1].of.i32;

    uint8_t* rawMemory = wasmtime_memory_data(self->context, &self->memory);
    if (rawMemory && len > 0) {
        // Append result to C++ string
        self->currentOutputBuffer.append(reinterpret_cast<char*>(rawMemory + wasmPtr), len);
    }
    return nullptr;
}