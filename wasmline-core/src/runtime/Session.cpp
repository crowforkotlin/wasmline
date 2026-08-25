/**
 * Implements Core Wasm instance execution.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "wasmline/runtime/Session.h"

#include "wasmline/internal/logging/NativeLogger.h"
#include "wasmline/protocol/WasmlineProtocol.h"
#include "wasmline/internal/runtime/RuntimeConstants.h"
#include "wasmline/internal/wasi/WasiConfig.h"
#include "wasmline/internal/wasmtime/WasmtimeMessage.h"
#include <cstring>
#include <exception>
#include <vector>

namespace wasmline {
    /** Creates a Core Wasm session and its Wasmtime store. */
    Session::Session(wasm_engine_t* eng, wasmtime_module_t* mod, std::string k)
        : engine(eng ? wasmtime_engine_clone(eng) : nullptr), module(mod ? wasmtime_module_clone(mod) : nullptr), key(std::move(k)) {
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

    /** Releases the Wasmtime linker and store. */
    Session::~Session() {
        if (linker) wasmtime_linker_delete(linker);
        if (store) wasmtime_store_delete(store);
        if (module) wasmtime_module_delete(module);
        if (engine) wasm_engine_delete(engine);
        LOGI("[Wasmtime] Session Destroyed: %s", key.c_str());
    }

    bool Session::initialize() {
        std::lock_guard<std::mutex> lock(sessionMutex);
        if (isInitialized) return true;

        if (!store || !context || !linker || !module) {
            LOGE("[Wasmtime] Session --> Invalid state before initialization. store=%p context=%p linker=%p module=%p key=%s", store,
                 context, linker, module, key.c_str());
            return false;
        }

        if (!configureWasi()) return false;
        if (!registerHostFunctions()) return false;
        if (!instantiate()) return false;
        if (!initializeMemory()) return false;
        if (!runInitialization()) return false;

        isInitialized = true;
        LOGI("[Wasmtime] Session --> Initialized: %s", key.c_str());
        return true;
    }

    bool Session::configureWasi() {
        wasmtime_error_t* defineWasiErr = wasmtime_linker_define_wasi(linker);
        if (defineWasiErr) {
            LOGE("[Wasmtime] Session --> Define WASI failed: %s", wasmtime::errorMessage(defineWasiErr).c_str());
            wasmtime_error_delete(defineWasiErr);
            return false;
        }
        wasi_config_t* wasi = wasi_config_new();
        if (!wasi) {
            LOGE("[Wasmtime] Session --> Create WASI config failed.");
            return false;
        }
        wasi::configure(wasi, "[Wasmtime-Wasi] logger");
        wasmtime_error_t* wasiErr = wasmtime_context_set_wasi(context, wasi);
        if (wasiErr) {
            LOGE("[Wasmtime] Session --> Configure WASI failed: %s", wasmtime::errorMessage(wasiErr).c_str());
            wasmtime_error_delete(wasiErr);
            wasi_config_delete(wasi);
            return false;
        }
        return true;
    }

    bool Session::instantiate() {
        wasm_trap_t* trap = nullptr;
        wasmtime_error_t* error = wasmtime_linker_instantiate(linker, context, module, &instance, &trap);
        if (error) {
            LOGE("[Wasmtime] Session --> Instantiate failed: %s", wasmtime::errorMessage(error).c_str());
            wasmtime_error_delete(error);
            return false;
        }
        if (trap) {
            LOGE("[Wasmtime] Session --> Instantiate trapped: %s", wasmtime::trapMessage(trap).c_str());
            wasm_trap_delete(trap);
            return false;
        }
        return true;
    }

    bool Session::initializeMemory() {
        wasmtime_extern_t memoryExport{};
        if (wasmtime_instance_export_get(context, &instance, "memory", 6, &memoryExport) && memoryExport.kind == WASMTIME_EXTERN_MEMORY) {
            memory = memoryExport.of.memory;
            hasMemory = true;
            return true;
        }
        LOGE("[Wasmtime] Session --> Memory export is unavailable.");
        hasMemory = false;
        return false;
    }

    bool Session::runInitialization() {
        wasm_trap_t* trap = nullptr;
        wasmtime_extern_t reactorInit{};
        if (wasmtime_instance_export_get(context, &instance, "_initialize", 11, &reactorInit) && reactorInit.kind == WASMTIME_EXTERN_FUNC) {
            wasmtime_error_t* error = wasmtime_func_call(context, &reactorInit.of.func, nullptr, 0, nullptr, 0, &trap);
            if (trap) {
                LOGE("[Wasmtime] Session --> Reactor initialization trapped: %s", wasmtime::trapMessage(trap).c_str());
                wasm_trap_delete(trap);
                trap = nullptr;
                return false;
            }
            if (error) {
                LOGE("[Wasmtime] Session --> Reactor initialization failed: %s", wasmtime::errorMessage(error).c_str());
                wasmtime_error_delete(error);
                return false;
            }
        }

        wasmtime_extern_t wasmlineInit{};
        if (wasmtime_instance_export_get(context, &instance, kWasmlineInitExportName.data(), kWasmlineInitExportName.size(),
                                         &wasmlineInit) &&
            wasmlineInit.kind == WASMTIME_EXTERN_FUNC) {
            wasmtime_error_t* error = wasmtime_func_call(context, &wasmlineInit.of.func, nullptr, 0, nullptr, 0, &trap);
            if (trap) {
                LOGE("[Wasmtime] Session --> Wasmline initialization trapped: %s", wasmtime::trapMessage(trap).c_str());
                wasm_trap_delete(trap);
                return false;
            }
            if (error) {
                LOGE("[Wasmtime] Session --> Wasmline initialization failed: %s", wasmtime::errorMessage(error).c_str());
                wasmtime_error_delete(error);
                return false;
            }
        }
        return true;
    }

    /** Sets the handler for outbound host calls.
     *
     * @param handler Handler ownership is transferred to the session.
     */
    void Session::setOutboundHandler(std::unique_ptr<OutboundHandler> handler) {
        std::lock_guard<std::mutex> lock(sessionMutex);
        this->outbound.handler = std::move(handler);
    }

    /** Invokes the Core Wasmline entry point.
     *
     * @param action Action name.
     * @param actionLen Action name length.
     * @param data Input data.
     * @param dataLen Input data length.
     * @return Encoded invocation result.
     */
    std::string Session::invokeInbound(const char* action, size_t actionLen, const char* data, size_t dataLen) {
        std::lock_guard<std::mutex> lock(sessionMutex);
        if (!isInitialized) {
            return WasmlineResponseCodec::failure(WasmlineErrorCode::ENGINE_NOT_INITIALIZED, "Wasmline session is not initialized.");
        }

        inbound.actionPtr = action;
        inbound.actionLen = actionLen;
        inbound.dataPtr = data;
        inbound.dataLen = dataLen;
        inbound.responseBuffer.clear();

        wasmtime_extern_t run_entry;
        wasm_trap_t* trap = nullptr;

        if (wasmtime_instance_export_get(context, &instance, kWasmlineEntryExportName.data(), kWasmlineEntryExportName.size(),
                                         &run_entry) &&
            run_entry.kind == WASMTIME_EXTERN_FUNC) {
            wasmtime_val_t args[2];
            args[0].kind = WASMTIME_I32;
            args[0].of.i32 = (int32_t)actionLen;
            args[1].kind = WASMTIME_I32;
            args[1].of.i32 = (int32_t)dataLen;
            wasmtime_error_t* error = wasmtime_func_call(context, &run_entry.of.func, args, 2, nullptr, 0, &trap);

            if (error || trap) {
                std::string message = "Wasmline Core invocation failed.";
                if (trap) {
                    message = wasmtime::trapMessage(trap);
                    LOGE("[Wasmtime] Session -> Wasm runtime trap: %s", message.c_str());
                    wasm_trap_delete(trap);
                    trap = nullptr;
                }
                if (error) {
                    const std::string errorMessage = wasmtime::errorMessage(error);
                    LOGE("[Wasmtime] Session -> Wasm function call error: %s", errorMessage.c_str());
                    if (message == "Wasmline Core invocation failed.") {
                        message = errorMessage;
                    }
                    wasmtime_error_delete(error);
                }

                inbound.actionPtr = nullptr;
                inbound.dataPtr = nullptr;
                return WasmlineResponseCodec::failure(WasmlineErrorCode::CORE_TRAP, message);
            }
        } else {
            LOGE(R"([Wasmtime] Session --> Wasm export get '%s' not found.)", wasmline::kWasmlineEntryExportName.data());
            inbound.actionPtr = nullptr;
            inbound.dataPtr = nullptr;
            return WasmlineResponseCodec::failure(WasmlineErrorCode::CORE_EXPORT_NOT_FOUND, "Wasmline entry export is not available.");
        }

        inbound.actionPtr = nullptr;
        inbound.dataPtr = nullptr;

        std::string result = std::move(inbound.responseBuffer);
        inbound.responseBuffer.clear();
        inbound.responseBuffer.shrink_to_fit();
        if (result.empty()) {
            return WasmlineResponseCodec::failure(WasmlineErrorCode::RESPONSE_MISSING, "Wasmline module returned no response.");
        }
        return result;
    }

    /** Registers the host functions required by the Core Wasmline ABI.
     *
     * @note Fixed-size stack arrays avoid heap allocation for the bridge signatures.
     */
    bool Session::registerHostFunctions() {

        if (!linker) {
            LOGE("[Wasmtime] Session --> Cannot register host functions because linker is null: %s", key.c_str());
            return false;
        }

        auto define = [&](const char* name, wasmtime_func_callback_t cb, std::initializer_list<wasm_valkind_t> params,
                          std::initializer_list<wasm_valkind_t> results) -> bool {
            wasm_valtype_t* p_arr[6] = {nullptr};
            wasm_valtype_t* r_arr[2] = {nullptr};

            int i = 0;
            for (auto k : params)
                if (i < 6) p_arr[i++] = wasm_valtype_new(k);
            int j = 0;
            for (auto k : results)
                if (j < 2) r_arr[j++] = wasm_valtype_new(k);

            wasm_valtype_vec_t p_vec, r_vec;
            wasm_valtype_vec_new(&p_vec, params.size(), p_arr);
            wasm_valtype_vec_new(&r_vec, results.size(), r_arr);

            wasm_functype_t* ty = wasm_functype_new(&p_vec, &r_vec);
            wasmtime_error_t* defineErr = wasmtime_linker_define_func(linker, "env", 3, name, strlen(name), ty, cb, nullptr, nullptr);

            wasm_functype_delete(ty);

            if (defineErr) {
                LOGE("[Wasmtime] Session --> Define host function '%s' failure: %s", name, wasmtime::errorMessage(defineErr).c_str());
                wasmtime_error_delete(defineErr);
                return false;
            }

            return true;
        };

        if (!define("bridge_inbound_get_size", bridge_inbound_get_size, {WASM_I32}, {WASM_I32})) return false;
        if (!define("bridge_inbound_copy_params", bridge_inbound_copy_params, {WASM_I32, WASM_I32, WASM_I32}, {})) return false;
        if (!define("bridge_inbound_set_response", bridge_inbound_set_response, {WASM_I32, WASM_I32}, {})) return false;

        if (!define("bridge_outbound_call_host", bridge_outbound_call_host, {WASM_I32, WASM_I32, WASM_I32, WASM_I32, WASM_I32, WASM_I32},
                    {WASM_I32}))
            return false;
        if (!define("bridge_outbound_get_response", bridge_outbound_get_response, {WASM_I32}, {})) return false;
        return true;
    }

    /** Returns the session stored in the caller context. */
    static Session* get_session(wasmtime_caller_t* caller) {
        return reinterpret_cast<Session*>(wasmtime_context_get_data(wasmtime_caller_context(caller)));
    }

    /** Returns the length of the selected inbound value. */
    wasm_trap_t* Session::bridge_inbound_get_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                  wasmtime_val_t* results, size_t nresults) {
        auto* self = get_session(caller);
        int32_t type = args[0].of.i32;
        results[0].kind = WASMTIME_I32;
        results[0].of.i32 = (int32_t)(type == 0 ? self->inbound.actionLen : self->inbound.dataLen);
        return nullptr;
    }

    /** Copies an inbound value into Wasm memory.
     *
     * @note The requested length is limited to the available host data.
     * @warning The Wasm destination range must be valid for the current memory.
     */
    wasm_trap_t* Session::bridge_inbound_copy_params(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                     wasmtime_val_t* results, size_t nresults) {
        Session* self = get_session(caller);
        if (!self->hasMemory) return wasmtime_trap_new("Memory not available", 20);

        int32_t type = args[0].of.i32;
        int32_t wasmPtr = args[1].of.i32;
        int32_t len = args[2].of.i32;

        const char* srcData = (type == 0) ? self->inbound.actionPtr : self->inbound.dataPtr;
        size_t maxLen = (type == 0) ? self->inbound.actionLen : self->inbound.dataLen;

        if (len < 0 || static_cast<size_t>(len) > maxLen) len = (int32_t)maxLen;

        uint8_t* rawMemory = wasmtime_memory_data(self->context, &self->memory);
        if (rawMemory && srcData && len > 0) {
            memcpy(rawMemory + wasmPtr, srcData, len);
        }
        return nullptr;
    }

    /** Copies an outbound response from Wasm memory to the host buffer. */
    wasm_trap_t* Session::bridge_inbound_set_response(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                      wasmtime_val_t* results, size_t nresults) {
        auto* self = get_session(caller);
        if (!self->hasMemory) return wasmtime_trap_new("Memory not available", 20);

        int32_t wasmPtr = args[0].of.i32;
        int32_t len = args[1].of.i32;

        uint8_t* rawMemory = wasmtime_memory_data(self->context, &self->memory);
        if (rawMemory && len > 0) {
            self->inbound.responseBuffer.append(reinterpret_cast<char*>(rawMemory + wasmPtr), len);
        }
        return nullptr;
    }

    /** Sends an outbound request to the host handler.
     *
     * @note The memory views remain valid during the synchronous host call.
     * @warning The Wasm pointers and lengths must describe valid memory ranges.
     */
    wasm_trap_t* Session::bridge_outbound_call_host(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                    wasmtime_val_t* results, size_t nresults) {
        Session* self = get_session(caller);
        auto* ctx = wasmtime_caller_context(caller);
        uint8_t* mem = wasmtime_memory_data(ctx, &self->memory);
        size_t mem_size = wasmtime_memory_size(ctx, &self->memory);

        int32_t aPtr = args[0].of.i32;
        int32_t aLen = args[1].of.i32;
        int32_t pPtr = args[2].of.i32;
        int32_t pLen = args[3].of.i32;

        int32_t outPtr = args[4].of.i32;
        int32_t outCap = args[5].of.i32;

        std::string_view action(reinterpret_cast<char*>(mem + aPtr), aLen);
        std::string_view payload(reinterpret_cast<char*>(mem + pPtr), pLen);

        std::string resultData;
        if (self->outbound.handler) {
            try {
                resultData = self->outbound.handler->onOutboundInvoke(action, payload);
            } catch (const std::exception& error) {
                resultData = WasmlineResponseCodec::failure(WasmlineErrorCode::HANDLER_FAILED, error.what());
            } catch (...) {
                resultData = WasmlineResponseCodec::failure(WasmlineErrorCode::HANDLER_FAILED, "Wasmline outbound action handler failed.");
            }
        } else {
            resultData = WasmlineResponseCodec::failure(WasmlineErrorCode::ACTION_NOT_BOUND, "No Wasmline outbound action is bound.");
        }

        int32_t resultSize = (int32_t)resultData.size();

        if (resultSize <= outCap) {
            if (resultSize > 0) {
                uint8_t* current_mem = wasmtime_memory_data(ctx, &self->memory);
                memcpy(current_mem + outPtr, resultData.data(), resultSize);
            }
            self->outbound.responseBuffer.clear();
            results[0].of.i32 = resultSize;
        } else {
            self->outbound.responseBuffer = std::move(resultData);
            results[0].of.i32 = -resultSize;
        }
        results[0].kind = WASMTIME_I32;
        return nullptr;
    }

    /** Copies the buffered host response into Wasm memory. */
    wasm_trap_t* Session::bridge_outbound_get_response(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                       wasmtime_val_t* results, size_t nresults) {
        Session* self = get_session(caller);
        uint8_t* mem = wasmtime_memory_data(self->context, &self->memory);
        int32_t ptr = args[0].of.i32;

        if (!self->outbound.responseBuffer.empty()) {
            memcpy(mem + ptr, self->outbound.responseBuffer.data(), self->outbound.responseBuffer.size());
        }
        self->outbound.responseBuffer.clear();
        self->outbound.responseBuffer.shrink_to_fit();
        return nullptr;
    }
} // namespace wasmline
