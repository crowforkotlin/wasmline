/**
 * Provides direct calls to Core Wasm exports.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#pragma once

#include <mutex>
#include <string>
#include <string_view>
#include <vector>

#include "wasmline/invocation/InvocationResult.h"
#include <wasmtime.h>

namespace wasmline {
    /** Provides direct calls to Core Wasm exports. */
    class RawModuleSession {
    public:
        /** Creates a session for a compiled Core Wasm module. */
        RawModuleSession(wasm_engine_t* engine, wasmtime_module_t* module, std::string key);

        /** Releases the session resources. */
        ~RawModuleSession();

        /** Instantiates the module and prepares its call state. */
        bool initialize();

        /** Invokes an exported Core Wasm function. */
        InvocationResult invoke(std::string_view exportName, const std::vector<RawValue>& arguments);

    private:
        std::string key_;
        wasm_engine_t* engine_;
        wasmtime_module_t* module_;
        wasmtime_store_t* store_ = nullptr;
        wasmtime_context_t* context_ = nullptr;
        wasmtime_linker_t* linker_ = nullptr;
        wasmtime_instance_t instance_{};
        bool initialized_ = false;
        std::mutex mutex_;

        static bool matchesType(wasm_valkind_t actual, RawValue::Type expected);

        static bool isSupportedType(wasm_valkind_t actual);

        static bool toWasmtimeValue(const RawValue& value, wasmtime_val_t* result);

        static bool toRawValue(const wasmtime_val_t& value, RawValue* result);
    };
} // namespace wasmline
