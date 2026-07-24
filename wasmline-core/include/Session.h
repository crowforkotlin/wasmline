/**
 * Manages a single Wasm instance execution environment.
 * Handles WASI configuration, memory mapping, and host function callbacks.
 *
 * Date: 2025-12-02
 * Author: crowforkotlin
 */

#pragma once

#include <memory>
#include <string>
#include <mutex>
#include "OutboundHandler.h"
#include "wasmtime.h"
#include "OutboundHandler.h"
#include <functional>

namespace wasmline {
    class Session {
    public:
        Session(wasm_engine_t* engine, wasmtime_module_t* module, std::string key);

        ~Session();

        // Initializes the session (Linker, WASI, Instance). Thread-safe.
        bool initialize();

        // Inbound Call (Host -> Wasm)
        std::string invokeInbound(const char* action, size_t actionLen, const char* data, size_t dataLen);

        // Injects the outbound host handler.
        void setOutboundHandler(std::unique_ptr<OutboundHandler> handler);

    private:
        // Inbound channel state populated by the host and read by Wasm.
        struct {
            const char* actionPtr = nullptr;
            size_t actionLen = 0;
            const char* dataPtr = nullptr;
            size_t dataLen = 0;
            std::string responseBuffer; // Stores the result produced by Wasm.
        } inbound;

        // Outbound channel state populated by Wasm and resolved by the host.
        struct {
            std::unique_ptr<OutboundHandler> handler;
            std::string responseBuffer; // Stores the host response until Wasm reads it.
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

        bool registerHostFunctions();

        // Static Host Callbacks
        static wasm_trap_t* bridge_inbound_get_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                    wasmtime_val_t* results, size_t nresults);

        static wasm_trap_t* bridge_inbound_copy_params(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                       wasmtime_val_t* results, size_t nresults);

        static wasm_trap_t* bridge_inbound_set_response(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                        wasmtime_val_t* results, size_t nresults);

        // Outbound Host Functions
        static wasm_trap_t* bridge_outbound_call_host(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                      wasmtime_val_t* results, size_t nresults);

        static wasm_trap_t* bridge_outbound_get_response(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                         wasmtime_val_t* results, size_t nresults);
    };
} // namespace wasmline