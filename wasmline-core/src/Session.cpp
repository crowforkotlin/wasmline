/**
 * Session Implementation.
 * Manages the Wasm instance lifecycle, WASI configuration, memory mapping,
 * and registration of host functions.
 *
 * 2025-12-02
 * @author crowforkotlin / crowforkotlin@gmail.com
 */

#include "Session.h"
#include "Logger.h"
#include "Consts.h"
#include "OutboundHandler.h"
#include <cstring>
#include <vector>

namespace wasmline {
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
    Session::Session(wasm_engine_t* eng, wasmtime_module_t* mod, std::string k) : engine(eng), module(mod), key(std::move(k)) {
        if (!engine) {
            LOGE("[Wasmtime] Session --> Cannot create store because engine is null: %s", key.c_str());
            return;
        }

        store = wasmtime_store_new(engine, this, nullptr);
        if (!store) {
            LOGE("[Wasmtime] Session --> Failed to create store: %s", key.c_str());
            return;
        }

        context = wasmtime_store_context(store);
        if (!context) {
            LOGE("[Wasmtime] Session --> Failed to acquire store context: %s", key.c_str());
            return;
        }

        linker = wasmtime_linker_new(engine);
        if (!linker) {
            LOGE("[Wasmtime] Session --> Failed to create linker: %s", key.c_str());
        }
    }

    /**
     * Destructor.
     * Releases resources associated with the Linker and Store.
     *
     * 2025-12-02
     * @author crowforkotlin
     */
    Session::~Session() {
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
    bool Session::initialize() {
        std::lock_guard<std::mutex> lock(sessionMutex);
        if (isInitialized) return true;

        if (!store || !context || !linker || !module) {
            LOGE("[Wasmtime] Session --> Invalid state before initialization. store=%p context=%p linker=%p module=%p key=%s", store,
                 context, linker, module, key.c_str());
            return false;
        }

        // =========================================================================================
        // STEP 1: Setup WASI (WebAssembly System Interface)
        // =========================================================================================
        // Defines the "Standard Library" for the Wasm environment.
        // Redirects Wasm stdout/stderr to our custom logger.
        wasmtime_error_t* defineWasiErr = wasmtime_linker_define_wasi(linker);
        if (defineWasiErr) {
            wasm_byte_vec_t error_msg;
            wasmtime_error_message(defineWasiErr, &error_msg);
            LOGE("[Wasmtime] Session --> 1. Define wasi failure: %s", error_msg.data);
            wasm_byte_vec_delete(&error_msg);
            wasmtime_error_delete(defineWasiErr);
            return false;
        }
        wasi_config_t* wasi = wasi_config_new();
        if (!wasi) {
            LOGE("[Wasmtime] Session --> 1. Failed to create wasi config.");
            return false;
        }
        wasi_config_inherit_env(wasi);
        wasi_config_set_stdout_custom(wasi, wasi_log_writer, nullptr, nullptr);
        wasi_config_set_stderr_custom(wasi, wasi_log_writer, nullptr, nullptr);
        wasmtime_error_t* wasiErr = wasmtime_context_set_wasi(context, wasi);
        if (wasiErr) {
            // Extract the error message string.
            wasm_byte_vec_t error_msg;
            wasmtime_error_message(wasiErr, &error_msg);
            LOGE("[Wasmtime] Session --> 1. Setup wasi failure: %s", error_msg.data);
            wasm_byte_vec_delete(&error_msg);
            wasmtime_error_delete(wasiErr);
            wasi_config_delete(wasi);
            return false;
        }

        LOGI("[Wasmtime] Session --> 1. Setup wasi success.");

        // =========================================================================================
        // STEP 2: Register Host Functions
        // =========================================================================================
        // Injects custom C++ functions into the Linker so the Wasm module can import and invokeInbound them.
        if (!registerHostFunctions()) {
            LOGE("[Wasmtime] Session --> 2. Register host functions failure.");
            return false;
        }
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
        // Executes the module initialization function for the instantiated Wasm reactor.
        wasmtime_extern_t init_func;
        if (wasmtime_instance_export_get(context, &instance, "_initialize", 11, &init_func) && init_func.kind == WASMTIME_EXTERN_FUNC) {
            wasmtime_func_call(context, &init_func.of.func, nullptr, 0, nullptr, 0, &trap);
            if (trap) {
                wasm_trap_delete(trap);
                trap = nullptr;
            }
        }

        // =========================================================================================
        // STEP 6: Run the generated Wasmline init export once (if present)
        // =========================================================================================
        // This gives the final wasmWasi module a deterministic one-time hook to execute user main().
        wasmtime_extern_t wasmline_init;
        if (wasmtime_instance_export_get(context, &instance, kWasmlineInitExportName.data(), kWasmlineInitExportName.size(),
                                         &wasmline_init) &&
            wasmline_init.kind == WASMTIME_EXTERN_FUNC) {
            wasmtime_error_t* initError = wasmtime_func_call(context, &wasmline_init.of.func, nullptr, 0, nullptr, 0, &trap);
            if (trap) {
                wasm_byte_vec_t msg;
                wasm_trap_message(trap, &msg);
                LOGE("[Wasmtime] Session --> 6. Wasm init trap: %s", msg.data);
                wasm_byte_vec_delete(&msg);
                wasm_trap_delete(trap);
                return false;
            }
            if (initError) {
                wasm_byte_vec_t msg;
                wasmtime_error_message(initError, &msg);
                LOGE("[Wasmtime] Session --> 6. Wasm init error: %s", msg.data);
                wasm_byte_vec_delete(&msg);
                wasmtime_error_delete(initError);
                return false;
            }
            LOGI("[Wasmtime] Session --> 6. Ran wasm init export '%s'.", kWasmlineInitExportName.data());
        }

        isInitialized = true;
        LOGI("[Wasmtime] Session --> 7. Initialized success: %s", key.c_str());
        return true;
    }

    /**
     * Injects the Outbound Handler.
     * Sets the handler responsible for processing requests from Wasm to Host.
     *
     * @param handler The handler instance
     *
     * 2025-12-26
     * @author crowforkotlin
     */
    void Session::setOutboundHandler(std::unique_ptr<OutboundHandler> handler) {
        std::lock_guard<std::mutex> lock(sessionMutex);
        this->outbound.handler = std::move(handler);
    }

    /**
     * Executes a specific action in the Wasm module.
     * Sets up context pointers, invokes the "App" exported function, and returns the result.
     *
     * @param action Action identifier string
     * @param actionLen Length of the action string
     * @param data Input binary data
     * @param dataLen Length of the data data
     * @return The output string/data from Wasm
     *
     * 2025-12-02
     * @author crowforkotlin
     */
    std::string Session::invokeInbound(const char* action, size_t actionLen, const char* data, size_t dataLen) {
        std::lock_guard<std::mutex> lock(sessionMutex);
        if (!isInitialized) return "";

        // 1. Set context pointers for Host Functions to access
        inbound.actionPtr = action;
        inbound.actionLen = actionLen;
        inbound.dataPtr = data;
        inbound.dataLen = dataLen;
        inbound.responseBuffer.clear();

        wasmtime_extern_t run_entry;
        wasm_trap_t* trap = nullptr;

        // 2. Call the "run_entry" function exported by Kotlin/Wasm
        if (wasmtime_instance_export_get(context, &instance, kWasmlineEntryExportName.data(), kWasmlineEntryExportName.size(),
                                         &run_entry)) {
            wasmtime_val_t args[2];
            args[0].kind = WASMTIME_I32;
            args[0].of.i32 = (int32_t)actionLen;
            args[1].kind = WASMTIME_I32;
            args[1].of.i32 = (int32_t)dataLen;
            wasmtime_error_t* error = wasmtime_func_call(context, &run_entry.of.func, args, 2, nullptr, 0, &trap);

            // 3. Handle errors or traps
            if (error || trap) {
                if (trap) {
                    wasm_byte_vec_t msg;
                    wasm_trap_message(trap, &msg);
                    LOGE("[Wasmtime] Session -> Wasm runtime trap: %s", msg.data);
                    wasm_byte_vec_delete(&msg);
                    wasm_trap_delete(trap);
                }
                if (error) {
                    wasm_byte_vec_t error_msg;
                    wasmtime_error_message(error, &error_msg);
                    LOGE("[Wasmtime] Session -> Wasm function call error: %s", error_msg.data);
                    wasm_byte_vec_delete(&error_msg);
                    wasmtime_error_delete(error);
                }

                // Clear pointers to prevent dangling references
                inbound.actionPtr = nullptr;
                inbound.dataPtr = nullptr;
                return "";
            }
        } else {
            LOGE(R"([Wasmtime] Session --> Wasm export get '%s' not found.)", wasmline::kWasmlineEntryExportName.data());
            inbound.actionPtr = nullptr;
            inbound.dataPtr = nullptr;
            return "";
        }

        // 4. Reset pointers
        inbound.actionPtr = nullptr;
        inbound.dataPtr = nullptr;

        std::string result = std::move(inbound.responseBuffer);
        inbound.responseBuffer.clear();
        inbound.responseBuffer.shrink_to_fit();
        return result;
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
    bool Session::registerHostFunctions() {

        if (!linker) {
            LOGE("[Wasmtime] Session --> Cannot register host functions because linker is null: %s", key.c_str());
            return false;
        }

        // Helper Lambda for fast registration using stack memory
        auto define = [&](const char* name, wasmtime_func_callback_t cb, std::initializer_list<wasm_valkind_t> params,
                          std::initializer_list<wasm_valkind_t> results) -> bool {
            // Stack allocation for the small fixed signatures used by the bridge.
            wasm_valtype_t* p_arr[6] = {nullptr};
            wasm_valtype_t* r_arr[2] = {nullptr};

            int i = 0;
            for (auto k : params)
                if (i < 6) p_arr[i++] = wasm_valtype_new(k);
            int j = 0;
            for (auto k : results)
                if (j < 2) r_arr[j++] = wasm_valtype_new(k);

            // Construct the vectors required by C API
            wasm_valtype_vec_t p_vec, r_vec;
            wasm_valtype_vec_new(&p_vec, params.size(), p_arr);
            wasm_valtype_vec_new(&r_vec, results.size(), r_arr);

            // Create function type and define it in the linker
            wasm_functype_t* ty = wasm_functype_new(&p_vec, &r_vec);
            wasmtime_error_t* defineErr = wasmtime_linker_define_func(linker, "env", 3, name, strlen(name), ty, cb, nullptr, nullptr);

            // Clean up function type (valtypes are handled internally by wasmtime)
            wasm_functype_delete(ty);

            if (defineErr) {
                wasm_byte_vec_t error_msg;
                wasmtime_error_message(defineErr, &error_msg);
                LOGE("[Wasmtime] Session --> Define host function '%s' failure: %s", name, error_msg.data);
                wasm_byte_vec_delete(&error_msg);
                wasmtime_error_delete(defineErr);
                return false;
            }

            return true;
        };

        // Register specific host functions
        if (!define("bridge_inbound_get_size", bridge_inbound_get_size, {WASM_I32}, {WASM_I32})) return false;
        if (!define("bridge_inbound_copy_params", bridge_inbound_copy_params, {WASM_I32, WASM_I32, WASM_I32}, {})) return false;
        if (!define("bridge_inbound_set_response", bridge_inbound_set_response, {WASM_I32, WASM_I32}, {})) return false;

        // Register Outbound
        if (!define("bridge_outbound_call_host", bridge_outbound_call_host, {WASM_I32, WASM_I32, WASM_I32, WASM_I32, WASM_I32, WASM_I32},
                    {WASM_I32}))
            return false;
        if (!define("bridge_outbound_get_response", bridge_outbound_get_response, {WASM_I32}, {})) return false;
        return true;
    }

    /**
     * Helper to retrieve the Session instance from the caller context.
     *
     * 2025-12-02
     * @author crowforkotlin
     */
    static Session* get_session(wasmtime_caller_t* caller) {
        return reinterpret_cast<Session*>(wasmtime_context_get_data(wasmtime_caller_context(caller)));
    }

    /**
     * Host Function: bridge_inbound_get_size
     * Called by Wasm to get the size of the input data provided by the Host.
     * Arg 0: Type (0=Action, 1=Input)
     * Return 0: Length of data (int32)
     *
     * 2025-12-02
     * @author crowforkotlin
     */
    wasm_trap_t* Session::bridge_inbound_get_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                  wasmtime_val_t* results, size_t nresults) {
        auto* self = get_session(caller);
        int32_t type = args[0].of.i32; // 0: Action, 1: Input
        results[0].kind = WASMTIME_I32;
        results[0].of.i32 = (int32_t)(type == 0 ? self->inbound.actionLen : self->inbound.dataLen);
        return nullptr;
    }

    /**
     * Host Function: bridge_inbound_copy_params
     * Called by Wasm to copy data from Host to Wasm Linear Memory.
     *
     * Arg 0: Source Type (0=Action, 1=Input)
     * Arg 1: Destination Address (Wasm Memory Pointer)
     * Arg 2: Length to copy
     *
     * 2025-12-02
     * @author crowforkotlin
     */
    wasm_trap_t* Session::bridge_inbound_copy_params(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                     wasmtime_val_t* results, size_t nresults) {
        Session* self = get_session(caller);
        if (!self->hasMemory) return wasmtime_trap_new("Memory not available", 20);

        int32_t type = args[0].of.i32;    // Source type
        int32_t wasmPtr = args[1].of.i32; // Destination Ptr
        int32_t len = args[2].of.i32;     // Length

        const char* srcData = (type == 0) ? self->inbound.actionPtr : self->inbound.dataPtr;
        size_t maxLen = (type == 0) ? self->inbound.actionLen : self->inbound.dataLen;

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
     * Host Function: bridge_inbound_set_response
     * Called by Wasm to copy data (results) from Wasm Linear Memory back to Host.
     *
     * Arg 0: Source Address (Wasm Memory Pointer)
     * Arg 1: Length to copy
     *
     * 2025-12-02
     * @author crowforkotlin
     */
    wasm_trap_t* Session::bridge_inbound_set_response(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                      wasmtime_val_t* results, size_t nresults) {
        auto* self = get_session(caller);
        if (!self->hasMemory) return wasmtime_trap_new("Memory not available", 20);

        int32_t wasmPtr = args[0].of.i32;
        int32_t len = args[1].of.i32;

        uint8_t* rawMemory = wasmtime_memory_data(self->context, &self->memory);
        if (rawMemory && len > 0) {
            // Append Wasm memory data to C++ output string buffer
            self->inbound.responseBuffer.append(reinterpret_cast<char*>(rawMemory + wasmPtr), len);
        }
        return nullptr;
    }

    /**
     * Host Function: bridge_outbound_call_host
     * Called by Wasm to invoke a Host function (Push Request).
     * Extracts action and payload from Wasm memory and delegates to the OutboundHandler.
     *
     * Arg 0: Action String Pointer (Wasm Memory Address)
     * Arg 1: Action String Length
     * Arg 2: Payload Data Pointer (Wasm Memory Address)
     * Arg 3: Payload Data Length
     * Return:
     *   >= 0: Number of bytes actually written (Fast Path, success)
     *   < 0 : Insufficient buffer space; the absolute value of the return value is the total length required (Slow Path).
     *
     * 2025-12-26
     * @author crowforkotlin
     */
    wasm_trap_t* Session::bridge_outbound_call_host(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                    wasmtime_val_t* results, size_t nresults) {
        Session* self = get_session(caller);
        auto* ctx = wasmtime_caller_context(caller);
        uint8_t* mem = wasmtime_memory_data(ctx, &self->memory);
        size_t mem_size = wasmtime_memory_size(ctx, &self->memory);

        // 1. extract input pointers
        int32_t aPtr = args[0].of.i32;
        int32_t aLen = args[1].of.i32;
        int32_t pPtr = args[2].of.i32;
        int32_t pLen = args[3].of.i32;

        // 2. extract output pre allocated area
        int32_t outPtr = args[4].of.i32;
        int32_t outCap = args[5].of.i32;

        // [Optimization] Use string_view to point directly to Wasm memory, ZERO COPY
        // Note: Wasm memory may expand during the host call, causing the pointer to become invalid, but it is safe during the synchronous
        // execution of this function.
        std::string_view action(reinterpret_cast<char*>(mem + aPtr), aLen);
        std::string_view payload(reinterpret_cast<char*>(mem + pPtr), pLen);

        // 3. execute business logic
        std::string resultData;
        if (self->outbound.handler) {
            // handler receives string_view
            resultData = self->outbound.handler->onOutboundInvoke(action, payload);
        }

        int32_t resultSize = (int32_t)resultData.size();

        // 4. fast path
        if (resultSize <= outCap) {
            if (resultSize > 0) {
                // Re-acquire the memory pointer in case the host callback triggered memory growth.
                uint8_t* current_mem = wasmtime_memory_data(ctx, &self->memory);
                memcpy(current_mem + outPtr, resultData.data(), resultSize);
            }
            self->outbound.responseBuffer.clear();
            results[0].of.i32 = resultSize;
        }
        // 5. slow path
        else {
            // Move, avoid copying
            self->outbound.responseBuffer = std::move(resultData);
            results[0].of.i32 = -resultSize;
        }
        results[0].kind = WASMTIME_I32;
        return nullptr;
    }

    /**
     * Host Function: bridge_outbound_get_response
     * Called by Wasm to retrieve the result of the last Host invocation (Pull Result).
     * Copies the buffered response data into Wasm memory.
     *
     * Arg 0: Destination Address (Wasm Memory Pointer)
     *
     * 2025-12-26
     * @author crowforkotlin
     */
    wasm_trap_t* Session::bridge_outbound_get_response(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                       wasmtime_val_t* results, size_t nresults) {
        Session* self = get_session(caller);
        uint8_t* mem = wasmtime_memory_data(self->context, &self->memory);
        int32_t ptr = args[0].of.i32;

        // Copy the buffered host response into the target Wasm memory address.
        if (!self->outbound.responseBuffer.empty()) {
            memcpy(mem + ptr, self->outbound.responseBuffer.data(), self->outbound.responseBuffer.size());
        }
        self->outbound.responseBuffer.clear();
        self->outbound.responseBuffer.shrink_to_fit();
        return nullptr;
    }
} // namespace wasmline