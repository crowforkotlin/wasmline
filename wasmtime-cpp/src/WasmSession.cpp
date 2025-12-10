/**
 * WasmSession Implementation.
 * Manages the Wasm instance lifecycle, WASI configuration, memory mapping,
 * and registration of host functions.
 *
 * 2025-12-02
 * @author crowforkotlin / crowforkotlin@gmail.com
 */

#include "WasmSession.h"
#include "WasmLogger.h"
#include "WasmHostHandler.h"
#include <cstring>
#include <vector>

/**
 * Log callback for WASI.
 * Captures stdout/stderr from the Wasm module and redirects it to the Native Logger (e.g., Logcat).
 * Truncates messages to 1024 bytes to prevent buffer overflow.
 *
 * @param data User data pointer (unused)
 * @param buffer Log content buffer
 * @param size Log content length
 * @return Bytes processed
 *
 * 2025-12-02
 * @author crowforkotlin
 */
static ptrdiff_t wasi_log_writer(void* data, const unsigned char* buffer, size_t size) {
    if (size > 0 && buffer) {
        std::string msg(reinterpret_cast<const char*>(buffer), size > 1024 ? 1024 : size);
        LOGI("[Wasmtime-Wasi] logger -> %s", msg.c_str());
    }
    return size;
}

/**
 * Constructor.
 * Initializes the Wasmtime Engine, Store, Context, and Linker.
 * Sets 'this' as user_data in the store to retrieve the session instance in callbacks.
 *
 * 2025-12-02
 * @author crowforkotlin
 */
WasmSession::WasmSession(wasm_engine_t* eng, wasmtime_module_t* mod, std::string k)
    : engine(eng), module(mod), key(std::move(k)) {
    store = wasmtime_store_new(engine, this, nullptr);
    context = wasmtime_store_context(store);
    linker = wasmtime_linker_new(engine);
}

/**
 * Destructor.
 * Releases resources associated with the Linker and Store.
 *
 * 2025-12-02
 * @author crowforkotlin
 */
WasmSession::~WasmSession() {
    if (linker) wasmtime_linker_delete(linker);
    if (store) wasmtime_store_delete(store);
    LOGI("[Wasmtime] Session Destroyed: %s", key.c_str());
}

/**
 * Initializes the Wasm Session (Core Logic).
 * 1. Configures WASI (Standard Library support).
 * 2. Registers Host Functions.
 * 3. Instantiates the Module.
 * 4. Exports Memory.
 * 5. Runs the _initialize function (Reactor Model).
 *
 * Note: This method is thread-safe and ensures initialization runs only once.
 *
 * 2025-12-02
 * @author crowforkotlin
 * @return true if initialization succeeds, false otherwise.
 */
bool WasmSession::initialize() {
    std::lock_guard<std::mutex> lock(sessionMutex);
    if (isInitialized) return true;

    // =========================================================================================
    // STEP 1: Setup WASI (WebAssembly System Interface)
    // =========================================================================================
    // Defines the "Standard Library" for the Wasm environment.
    // Redirects Wasm stdout/stderr to our custom logger.
    wasmtime_linker_define_wasi(linker);
    wasi_config_t* wasi = wasi_config_new();
    wasi_config_inherit_env(wasi);
    wasi_config_set_stdout_custom(wasi, wasi_log_writer, nullptr, nullptr);
    wasi_config_set_stderr_custom(wasi, wasi_log_writer, nullptr, nullptr);
    wasmtime_error_t* wasiErr = wasmtime_context_set_wasi(context, wasi);
    if (wasiErr)
    {
        // 1. 提取错误信息字符串
        wasm_byte_vec_t error_msg;
        wasmtime_error_message(wasiErr, &error_msg);
        LOGE("[Wasmtime] Session --> 1. Setup wasi failure: %s", error_msg.data);
        wasm_byte_vec_delete(&error_msg); // 释放字符串内存
        wasmtime_error_delete(wasiErr);   // 释放错误对象内存
        wasi_config_delete(wasi);         // 关键：失败时必须手动释放 config
        return false;
    }
    
    LOGI("[Wasmtime] Session --> 1. Setup wasi success.");
    
    // =========================================================================================
    // STEP 2: Register Host Functions
    // =========================================================================================
    // Injects custom C++ functions into the Linker so the Wasm module can import and call them.
    registerHostFunctions();
    LOGI("[Wasmtime] Session --> 2. Register host functions success.");
    
    // =========================================================================================
    // STEP 3: Instantiate Module
    // =========================================================================================
    // Links the module with dependencies, allocates memory, and runs the start function.
    wasm_trap_t* trap = nullptr;
    wasmtime_error_t* error = wasmtime_linker_instantiate(linker, context, module, &instance, &trap);
    if (error) {
        wasm_byte_vec_t msg;
        wasmtime_error_message(error, &msg);
        LOGE("[Wasmtime] Session --> 3. Instantiate linker error: %s", msg.data);
        wasm_byte_vec_delete(&msg);
        wasmtime_error_delete(error);
        return false;
    }
    if (trap) {
        wasm_byte_vec_t msg;
        wasm_trap_message(trap, &msg);
        LOGE("[Wasmtime] Session --> 3. Instantiate linker trap: %s", msg.data);
        wasm_byte_vec_delete(&msg);
        wasm_trap_delete(trap);
        return false;
    }
    LOGI("[Wasmtime] Session --> 3. Instantiate linker success.");
    
    // =========================================================================================
    // STEP 4: Export Memory
    // =========================================================================================
    // Retrieves the handle to the Wasm linear memory to allow direct Read/Write from C++.
    wasmtime_extern_t mem_ext;
    if (wasmtime_instance_export_get(context, &instance, "memory", 6, &mem_ext) && mem_ext.kind == WASMTIME_EXTERN_MEMORY) {
        LOGI("[Wasmtime] Session --> 4. Export memory success.");
        memory = mem_ext.of.memory;
        hasMemory = true;
    } else {
        LOGE("[Wasmtime] Session --> 4. Failed to export 'memory'. Copy functions will fail.");
        hasMemory = false;
        return false;
    }

    // =========================================================================================
    // STEP 5: Run _initialize (WASI Reactor Model)
    // =========================================================================================
    // Executes the initialization function (like a constructor or static block) for the Wasm module.
    wasmtime_extern_t init_func;
    if (wasmtime_instance_export_get(context, &instance, "_initialize", 11, &init_func)) {
        wasmtime_func_call(context, &init_func.of.func, nullptr, 0, nullptr, 0, &trap);
        if (trap) {
            wasm_trap_delete(trap);
        } 
    }

    isInitialized = true;
    LOGI("[Wasmtime] Session --> 5. Initialized success: %s", key.c_str());
    return true;
}

// [注入 Handler]
void WasmSession::setHostHandler(std::unique_ptr<WasmHostHandler> handler) {
    std::lock_guard<std::mutex> lock(sessionMutex);
    this->hostHandler = std::move(handler);
}


/**
 * Executes a specific action in the Wasm module.
 * Sets up context pointers, invokes the "App" exported function, and returns the result.
 *
 * @param action Action identifier string
 * @param actionLen Length of the action string
 * @param input Input binary data
 * @param inputLen Length of the input data
 * @return The output string/data from Wasm
 *
 * 2025-12-02
 * @author crowforkotlin
 */
std::string WasmSession::call(const char* action, size_t actionLen, const char* input, size_t inputLen) {
    std::lock_guard<std::mutex> lock(sessionMutex);
    if (!isInitialized) return "";

    // 1. Set context pointers for Host Functions to access
    currentActionPtr = action;
    currentActionLen = actionLen;
    currentInputPtr = input;
    currentInputLen = inputLen;
    currentOutputBuffer.clear();
    currentHostInvokeResult.clear();

    wasmtime_extern_t run_entry;
    wasm_trap_t* trap = nullptr;

    // 2. Call the "run_entry" function exported by Kotlin/Wasm
    if (wasmtime_instance_export_get(context, &instance, "WasmEntry", 9, &run_entry)) {
        wasmtime_error_t* error = wasmtime_func_call(context, &run_entry.of.func, nullptr, 0, nullptr, 0, &trap);
        
        // 3. Handle errors or traps
        if (error || trap) {
            if (trap) {
                wasm_byte_vec_t msg;
                wasm_trap_message(trap, &msg);
                LOGE("[Wasmtime] Session -> Runtime Trap: %s", msg.data);
                wasm_byte_vec_delete(&msg);
                wasm_trap_delete(trap);
            }
            if (error) wasmtime_error_delete(error);
            
            // Clear pointers to prevent dangling references
            currentActionPtr = nullptr;
            currentInputPtr = nullptr;
            return "";
        }
    } else {
        LOGE("[Wasmtime] Session --> Export 'WasmEntry' not found.");
    }

    // 4. Reset pointers
    currentActionPtr = nullptr;
    currentInputPtr = nullptr;

    currentHostInvokeResult.clear();
    currentHostInvokeResult.shrink_to_fit();

    return currentOutputBuffer;
}

/**
 * Registers all Host Functions to the Linker.
 * 
 * Performance Optimization:
 * Uses stack-allocated arrays (p_arr[4]) instead of std::vector to avoid heap allocations.
 * This ensures maximum performance for functions with a fixed small number of parameters.
 *
 * 2025-12-02
 * @author crowforkotlin
 */
void WasmSession::registerHostFunctions() {

    // Helper Lambda for fast registration using stack memory
    auto define = [&](
        const char* name, wasmtime_func_callback_t cb, 
        std::initializer_list<wasm_valkind_t> params, 
        std::initializer_list<wasm_valkind_t> results
    ) {
        // Stack allocation for parameters (Max 4 is sufficient)
        wasm_valtype_t* p_arr[4];
        wasm_valtype_t* r_arr[4];
        
        int i = 0;
        for (auto k : params) if (i < 4) p_arr[i++] = wasm_valtype_new(k);
        int j = 0;
        for (auto k : results) if (j < 4) r_arr[j++] = wasm_valtype_new(k);
        
        // Construct the vectors required by C API
        wasm_valtype_vec_t p_vec, r_vec;
        wasm_valtype_vec_new(&p_vec, params.size(), p_arr);
        wasm_valtype_vec_new(&r_vec, results.size(), r_arr);
        
        // Create function type and define it in the linker
        wasm_functype_t* ty = wasm_functype_new(&p_vec, &r_vec);
        wasmtime_linker_define_func(linker, "env", 3, name, strlen(name), ty, cb, nullptr, nullptr);
        
        // Clean up function type (valtypes are handled internally by wasmtime)
        wasm_functype_delete(ty);
    };

    // Register specific host functions
    define("host_get_size", host_get_size, {WASM_I32}, {WASM_I32}); 
    define("host_copy_to_memory", host_copy_to_memory, {WASM_I32, WASM_I32, WASM_I32}, {}); 
    define("host_read_from_memory", host_read_from_memory, {WASM_I32, WASM_I32}, {});

    // Register Outbound
    define("host_invoke_outbound", host_invoke_outbound, {WASM_I32, WASM_I32, WASM_I32, WASM_I32}, {WASM_I32});
    define("host_copy_outbound_result", host_copy_outbound_result, {WASM_I32}, {});
}

/**
 * Helper to retrieve the WasmSession instance from the caller context.
 *
 * 2025-12-02
 * @author crowforkotlin
 */
static WasmSession* get_session(wasmtime_caller_t* caller) {
    return reinterpret_cast<WasmSession*>(wasmtime_context_get_data(wasmtime_caller_context(caller)));
}

/**
 * Host Function: host_get_size
 * Called by Wasm to get the size of the input data provided by the Host.
 * Arg 0: Type (0=Action, 1=Input)
 * Return 0: Length of data (int32)
 *
 * 2025-12-02
 * @author crowforkotlin
 */
wasm_trap_t* WasmSession::host_get_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
    auto* self = get_session(caller);
    int32_t type = args[0].of.i32; // 0: Action, 1: Input
    results[0].kind = WASMTIME_I32;
    results[0].of.i32 = (int32_t)(type == 0 ? self->currentActionLen : self->currentInputLen);
    return nullptr;
}

/**
 * Host Function: host_copy_to_memory
 * Called by Wasm to copy data from Host to Wasm Linear Memory.
 * 
 * Arg 0: Source Type (0=Action, 1=Input)
 * Arg 1: Destination Address (Wasm Memory Pointer)
 * Arg 2: Length to copy
 *
 * 2025-12-02
 * @author crowforkotlin
 */
wasm_trap_t* WasmSession::host_copy_to_memory(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
    WasmSession* self = get_session(caller);
    if (!self->hasMemory) return wasmtime_trap_new("Memory not available", 20);

    int32_t type = args[0].of.i32;    // Source type
    int32_t wasmPtr = args[1].of.i32; // Destination Ptr
    int32_t len = args[2].of.i32;     // Length

    const char* srcData = (type == 0) ? self->currentActionPtr : self->currentInputPtr;
    size_t maxLen = (type == 0) ? self->currentActionLen : self->currentInputLen;

    // Safety: Clamp length to avoid reading past Host buffer
    if (len < 0 || static_cast<size_t>(len) > maxLen) len = (int32_t)maxLen;
    
    // Perform copy using raw memory pointer
    // Note: Ideally, check (wasmPtr + len) against memory bounds here as well
    uint8_t* rawMemory = wasmtime_memory_data(self->context, &self->memory);
    if (rawMemory && srcData && len > 0) {
        memcpy(rawMemory + wasmPtr, srcData, len);
    }
    return nullptr;
}

/**
 * Host Function: host_read_from_memory
 * Called by Wasm to copy data (results) from Wasm Linear Memory back to Host.
 *
 * Arg 0: Source Address (Wasm Memory Pointer)
 * Arg 1: Length to copy
 *
 * 2025-12-02
 * @author crowforkotlin
 */
wasm_trap_t* WasmSession::host_read_from_memory(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
    auto* self = get_session(caller);
    if (!self->hasMemory) return wasmtime_trap_new("Memory not available", 20);

    int32_t wasmPtr = args[0].of.i32;
    int32_t len = args[1].of.i32;

    uint8_t* rawMemory = wasmtime_memory_data(self->context, &self->memory);
    if (rawMemory && len > 0) {
        // Append Wasm memory data to C++ output string buffer
        self->currentOutputBuffer.append(reinterpret_cast<char*>(rawMemory + wasmPtr), len);
    }
    return nullptr;
}

// [实现] Wasm -> Host (Push Request)
wasm_trap_t* WasmSession::host_invoke_outbound(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
    WasmSession* self = get_session(caller);
    auto* ctx = wasmtime_caller_context(caller);
    uint8_t* mem = wasmtime_memory_data(ctx, &self->memory);

    // 1. 从 Wasm 内存读取请求
    int32_t aPtr = args[0].of.i32; int32_t aLen = args[1].of.i32;
    int32_t pPtr = args[2].of.i32; int32_t pLen = args[3].of.i32;

    std::string action((char*)(mem + aPtr), aLen);
    std::string payload((char*)(mem + pPtr), pLen);

    // 2. 调用 Host 回调
    if (self->hostHandler) {
        // [关键] 执行结果被 Deep Copy 到了 currentHostInvokeResult 中暂存
        self->currentHostInvokeResult = self->hostHandler->invoke(action, payload);
    } else {
        self->currentHostInvokeResult = "";
    }

    // 3. 告诉 Wasm 结果有多大
    results[0].kind = WASMTIME_I32;
    results[0].of.i32 = (int32_t)self->currentHostInvokeResult.size();
    return nullptr;
}

// [实现] Wasm -> Host (Pull Result)
wasm_trap_t* WasmSession::host_copy_outbound_result(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
    WasmSession* self = get_session(caller);
    uint8_t* mem = wasmtime_memory_data(self->context, &self->memory);
    int32_t ptr = args[0].of.i32;

    // [关键] 把暂存的结果拷贝到 Wasm 指定的内存地址
    if (!self->currentHostInvokeResult.empty()) {
        memcpy(mem + ptr, self->currentHostInvokeResult.data(), self->currentHostInvokeResult.size());
    }
    self->currentHostInvokeResult.clear();
    self->currentHostInvokeResult.shrink_to_fit();
    return nullptr;
}