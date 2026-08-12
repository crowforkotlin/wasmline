/**
 * Provides isolated execution state for a Component Model instance.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#pragma once

#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <vector>
#include <unordered_map>

#include "wasmline/invocation/InvocationResult.h"
#include "wasmline/value/ComponentValue.h"
#include <wasmtime/component/component.h>
#include <wasmtime/component/func.h>
#include <wasmtime/component/instance.h>
#include <wasmtime/component/linker.h>
#include <wasmtime/component/types/component.h>
#include <wasmtime/component/types/func.h>
#include <wasmtime/component/types/val.h>
#include <wasmtime/component/val.h>
#include <wasmtime/store.h>

namespace wasmline {
    class ComponentHostHandler;
    class OutboundHandler;

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

        /** Sets the handler and codec expected by the imported Wasmline RPC interface. */
        void setOutboundHandler(std::unique_ptr<OutboundHandler> handler, std::string codec);

        /** Sets the typed handler for non-WASI Component Model imports. */
        void setComponentHostHandler(std::unique_ptr<ComponentHostHandler> handler);

        /** Drops one owned resource previously returned by this session. */
        bool dropResource(const ComponentResourceReference& reference);

        /** Creates one owned Host resource for an imported Component resource type. */
        bool createHostResource(std::string_view interfaceName, std::string_view resourceName, uint32_t representation,
                                ComponentResourceReference* reference);

        /** Lowers a resource carrier after validating it against this session. */
        bool lowerResource(const ComponentValue& value, const wasmtime_component_valtype_t& type, wasmtime_component_val_t* result);

        /** Lifts a Wasmtime resource and records its session-scoped lifetime. */
        bool liftResource(const wasmtime_component_val_t& value, const wasmtime_component_valtype_t& type, ComponentValue* result,
                          std::vector<uint64_t>* transientBorrowed);

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
        std::unique_ptr<OutboundHandler> outboundHandler_;
        std::string codec_;
        std::unique_ptr<ComponentHostHandler> componentHostHandler_;
        struct ComponentHostBinding;
        std::vector<std::unique_ptr<ComponentHostBinding>> componentHostBindings_;

        struct ResourceEntry {
            uint32_t typeId = 0;
            uint64_t handleId = 0;
            uint32_t generation = 0;
            ComponentResourceOwnership ownership = ComponentResourceOwnership::OWN;
            ComponentResourceOrigin origin = ComponentResourceOrigin::GUEST;
            wasmtime_component_resource_any_t* value = nullptr;
            wasmtime_component_resource_type_t* type = nullptr;
            uint32_t hostRepresentation = 0;
            uint32_t hostType = 0;
        };
        std::unordered_map<uint64_t, ResourceEntry> resources_;
        std::unordered_map<uint32_t, wasmtime_component_resource_type_t*> resourceTypes_;
        std::unordered_map<uint32_t, ComponentResourceReference> hostRepresentations_;
        uint64_t nextResourceHandle_ = 1;
        uint32_t nextResourceType_ = 1;
        uint32_t nextResourceGeneration_ = 1;
        uint32_t nextHostResourceType_ = 1;
        bool resourceConversionInvalid_ = false;

        struct ImportedResourceBinding {
            ComponentSession* session = nullptr;
            std::string interfaceName;
            std::string resourceName;
            uint32_t hostType = 0;
            wasmtime_component_resource_type_t* type = nullptr;
        };
        std::vector<std::unique_ptr<ImportedResourceBinding>> importedResourceBindings_;

        bool registerResource(wasmtime_component_resource_any_t* value, ComponentResourceOwnership ownership,
                              ComponentResourceOrigin origin, ComponentResourceReference* reference);
        bool dropResourceUnlocked(const ComponentResourceReference& reference);
        bool notifyHostResourceDrop(uint32_t representation, uint32_t hostType = 0);
        void clearTransientResources(const std::vector<uint64_t>& handles);
        void clearResources();
        void clearHostRepresentations();

        bool registerRpcImports();

        bool registerComponentHostImports();

        static wasmtime_error_t* invokeHost(void* data, wasmtime_context_t* context, const wasmtime_component_func_type_t* functionType,
                                            wasmtime_component_val_t* arguments, size_t argumentCount, wasmtime_component_val_t* results,
                                            size_t resultCount);

        static wasmtime_error_t* invokeComponentHost(void* data, wasmtime_context_t* context,
                                                     const wasmtime_component_func_type_t* functionType,
                                                     wasmtime_component_val_t* arguments, size_t argumentCount,
                                                     wasmtime_component_val_t* results, size_t resultCount);

        static wasmtime_error_t* dropImportedResource(void* data, wasmtime_context_t* context, uint32_t representation);

        wasmtime_error_t* handleImportedResourceDrop(const ImportedResourceBinding& binding, uint32_t representation);

        wasmtime_error_t* handleHostInvoke(const wasmtime_component_func_type_t* functionType, wasmtime_component_val_t* arguments,
                                           size_t argumentCount, wasmtime_component_val_t* results, size_t resultCount);

        wasmtime_error_t* handleComponentHostInvoke(const ComponentHostBinding& binding, const wasmtime_component_func_type_t* functionType,
                                                    wasmtime_component_val_t* arguments, size_t argumentCount,
                                                    wasmtime_component_val_t* results, size_t resultCount);

        bool toWasmtimeValue(const ComponentValue& value, const wasmtime_component_valtype_t& type, wasmtime_component_val_t* result);

        bool fromWasmtimeValue(const wasmtime_component_val_t& value, const wasmtime_component_valtype_t& type, ComponentValue* result,
                               std::vector<uint64_t>* transientBorrowed = nullptr);

        static bool hasWasmTrace(const wasmtime_error_t* error);
    };
} // namespace wasmline
