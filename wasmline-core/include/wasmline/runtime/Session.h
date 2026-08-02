/**
 * Manages one Core Wasm instance and its host callbacks.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#pragma once

#include <memory>
#include <mutex>
#include <string>

#include <wasmtime.h>

#include "wasmline/runtime/OutboundHandler.h"

namespace wasmline {
    /** Manages one Core Wasm instance and its host callbacks. */
    class Session {
    public:
        /** Creates a session for a compiled Core Wasm module. */
        Session(wasm_engine_t* engine, wasmtime_module_t* module, std::string key);

        /** Releases the session resources. */
        ~Session();

        /** Initializes the linker, WASI, instance, and host callbacks. */
        bool initialize();

        /** Invokes the Core Wasmline entry point. */
        std::string invokeInbound(const char* action, size_t actionLen, const char* data, size_t dataLen);

        /** Sets the handler for outbound host calls. */
        void setOutboundHandler(std::unique_ptr<OutboundHandler> handler);

    private:
        struct {
            const char* actionPtr = nullptr;
            size_t actionLen = 0;
            const char* dataPtr = nullptr;
            size_t dataLen = 0;
            std::string responseBuffer;
        } inbound;

        struct {
            std::unique_ptr<OutboundHandler> handler;
            std::string responseBuffer;
        } outbound;

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

        bool configureWasi();

        bool registerHostFunctions();

        bool instantiate();

        bool initializeMemory();

        bool runInitialization();

        static wasm_trap_t* bridge_inbound_get_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                    wasmtime_val_t* results, size_t nresults);

        static wasm_trap_t* bridge_inbound_copy_params(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                       wasmtime_val_t* results, size_t nresults);

        static wasm_trap_t* bridge_inbound_set_response(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                        wasmtime_val_t* results, size_t nresults);

        static wasm_trap_t* bridge_outbound_call_host(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                      wasmtime_val_t* results, size_t nresults);

        static wasm_trap_t* bridge_outbound_get_response(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                         wasmtime_val_t* results, size_t nresults);
    };
} // namespace wasmline
