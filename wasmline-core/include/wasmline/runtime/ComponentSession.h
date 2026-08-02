/**
 * Provides isolated execution state for a Component Model instance.
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
#include "wasmline/value/ComponentValue.h"
#include <wasmtime/component/component.h>
#include <wasmtime/component/func.h>
#include <wasmtime/component/instance.h>
#include <wasmtime/component/linker.h>
#include <wasmtime/component/types/component.h>
#include <wasmtime/component/types/func.h>
#include <wasmtime/component/types/val.h>
#include <wasmtime/store.h>

namespace wasmline {
    /** Provides isolated execution state for a Component Model instance. */
    class ComponentSession {
    public:
        /** Creates a session for a compiled component. */
        ComponentSession(wasm_engine_t* engine, wasmtime_component_t* component, std::string key);

        /** Releases the session resources. */
        ~ComponentSession();

        /** Instantiates the component and prepares its call state. */
        bool initialize();

        /** Invokes an exported component function. */
        InvocationResult invoke(std::string_view exportName, const std::vector<ComponentValue>& arguments);

    private:
        std::string key_;
        wasm_engine_t* engine_;
        wasmtime_component_t* component_;
        wasmtime_store_t* store_ = nullptr;
        wasmtime_context_t* context_ = nullptr;
        wasmtime_component_linker_t* linker_ = nullptr;
        wasmtime_component_instance_t instance_{};
        bool initialized_ = false;
        std::mutex mutex_;

        static bool toWasmtimeValue(const ComponentValue& value, const wasmtime_component_valtype_t& type,
                                    wasmtime_component_val_t* result);

        static bool fromWasmtimeValue(const wasmtime_component_val_t& value, const wasmtime_component_valtype_t& type,
                                      ComponentValue* result);

        static bool hasWasmTrace(const wasmtime_error_t* error);
    };
} // namespace wasmline
