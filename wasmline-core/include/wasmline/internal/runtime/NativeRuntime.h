/**
 * Defines internal native Wasmline runtime coordination.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */

#pragma once

#include <cstdint>
#include <memory>
#include <shared_mutex>
#include <string>
#include <string_view>
#include <vector>

#include "wasmline/internal/runtime/ComponentSessionRegistry.h"
#include "wasmline/internal/runtime/RawSessionRegistry.h"
#include "wasmline/internal/runtime/ServiceSessionRegistry.h"
#include "wasmline/runtime/ArtifactLoadResult.h"
#include "wasmline/runtime/WasmlineArtifactFormat.h"

namespace wasmline {
    class ComponentHostHandler;
    class OutboundHandler;

    /**
     * Coordinates engine, artifact, and session lifecycles for the native backend.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     */
    class NativeRuntime final {
    public:
        /** Returns the process-wide native runtime. */
        static NativeRuntime& instance();

        NativeRuntime(const NativeRuntime&) = delete;
        NativeRuntime& operator=(const NativeRuntime&) = delete;

        /** Initializes the selected engine. */
        bool warmup(bool usePulley);

        /** Releases all sessions, artifacts, and the engine. */
        void shutdown();

        /** Loads a Core Wasm artifact. */
        ArtifactLoadResult loadModule(const std::string& key, const std::string& path, WasmlineArtifactFormat format, bool unsafe);

        /** Loads a Component Model artifact. */
        ArtifactLoadResult loadComponent(const std::string& key, const std::string& path, WasmlineArtifactFormat format, bool unsafe);

        /** Releases an artifact and every associated session. */
        void releaseArtifact(const std::string& key);

        /** Installs an outbound Service handler. */
        void setOutboundHandler(const std::string& key, std::string codec, std::unique_ptr<OutboundHandler> handler);

        /** Installs a typed Component host handler. */
        bool setComponentHostHandler(const std::string& key, std::unique_ptr<ComponentHostHandler> handler);

        /** Invokes the Core Wasmline Service entry point. */
        std::string invokeInbound(const std::string& key, const char* action, size_t actionLen, const char* data, size_t dataLen);

        /** Invokes an export on an implicit raw session. */
        InvocationResult invokeRaw(const std::string& key, std::string_view exportName, const std::vector<RawValue>& arguments);

        /** Returns reflected Core Wasm exports. */
        std::vector<RawExportDefinition> describeRawModule(const std::string& artifactKey);

        /** Instantiates an explicit raw session. */
        InvocationResult instantiateRawModule(const std::string& artifactKey, const std::string& sessionKey,
                                              const std::vector<RawImportDefinition>& imports, RawImportCallback callback,
                                              RawImportBufferFree bufferFree, void* callbackUser, RawImportUserFinalizer userFinalizer,
                                              std::string memoryExportName);

        /** Invokes an explicit raw session. */
        InvocationResult invokeRawInstance(const std::string& sessionKey, std::string_view exportName,
                                           const std::vector<RawValue>& arguments);

        /** Reads explicit raw session memory into caller-owned storage. */
        InvocationResult readRawMemory(const std::string& sessionKey, uint64_t offset, uint8_t* destination, uint64_t length);

        /** Writes explicit raw session memory. */
        InvocationResult writeRawMemory(const std::string& sessionKey, uint64_t offset, const uint8_t* bytes, uint64_t length);

        /** Returns explicit raw session memory size. */
        InvocationResult rawMemorySize(const std::string& sessionKey, bool pages);

        /** Grows explicit raw session memory. */
        InvocationResult growRawMemory(const std::string& sessionKey, uint64_t deltaPages);

        /** Releases one explicit raw session. */
        void releaseRawInstance(const std::string& sessionKey);

        /** Invokes an export on an implicit Component session. */
        InvocationResult invokeComponent(const std::string& key, std::string_view exportName, const std::vector<ComponentValue>& arguments);

        /** Instantiates one explicit Component session. */
        bool instantiateComponent(const std::string& artifactKey, const std::string& instanceKey,
                                  std::unique_ptr<ComponentHostHandler> handler);

        /** Invokes one explicit Component session. */
        InvocationResult invokeComponentInstance(const std::string& instanceKey, std::string_view exportName,
                                                 const std::vector<ComponentValue>& arguments);

        /** Drops one Component resource. */
        bool dropComponentResource(const std::string& instanceKey, const ComponentResourceReference& reference);

        /** Creates one imported Host resource. */
        bool createComponentHostResource(const std::string& instanceKey, std::string_view interfaceName, std::string_view resourceName,
                                         uint32_t representation, ComponentResourceReference* reference);

        /** Releases one explicit Component session. */
        void releaseComponentInstance(const std::string& instanceKey);

    private:
        NativeRuntime();

        bool ensureEngineForArtifact(WasmlineArtifactFormat format, const std::string& path);
        bool isEngineReadyForArtifact(WasmlineArtifactFormat format);
        bool hasLoadedArtifacts() const;

        mutable std::shared_mutex lifecycleMutex_;
        ServiceSessionRegistry serviceSessions_;
        RawSessionRegistry rawSessions_;
        ComponentSessionRegistry componentSessions_;
    };
} // namespace wasmline
