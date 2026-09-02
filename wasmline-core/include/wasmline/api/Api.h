/**
 * Defines the native Wasmline API facade.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */

#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

#include "wasmline/invocation/InvocationResult.h"
#include "wasmline/invocation/RawWasmTypes.h"
#include "wasmline/api/NativeRuntimeIdentity.h"
#include "wasmline/runtime/ArtifactLoadResult.h"
#include "wasmline/runtime/WasmlineArtifactFormat.h"

namespace wasmline {
    class ComponentHostHandler;
    class OutboundHandler;

    /**
     * Provides the native Wasmline API facade.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     */
    class Api {
    public:
        /** Initializes the selected engine without invalidating loaded artifacts. */
        static bool warmupEngine(bool usePulley);

        /** Returns the immutable native runtime identity. */
        static const WasmlineNativeRuntimeIdentity& nativeRuntimeIdentity();

        /** Releases the global engine, artifacts, and sessions. */
        static void releaseEngine();

        /** Converts a stable native bridge code to a physical artifact format. */
        static bool tryArtifactFormatFromCode(int32_t formatCode, WasmlineArtifactFormat* format);

        /** Loads a Core Wasm artifact with an explicit physical format. */
        static ArtifactLoadResult loadModule(const std::string& key, const std::string& path, WasmlineArtifactFormat artifactFormat);

        /** Loads a Core Wasm artifact with an explicit format without cache synchronization. */
        static ArtifactLoadResult loadModuleUnsafe(const std::string& key, const std::string& path, WasmlineArtifactFormat artifactFormat);

        /** Loads a Component Model artifact with an explicit physical format. */
        static ArtifactLoadResult loadComponent(const std::string& key, const std::string& path, WasmlineArtifactFormat artifactFormat);

        /** Loads a Component Model artifact with an explicit format without cache synchronization. */
        static ArtifactLoadResult loadComponentUnsafe(const std::string& key, const std::string& path,
                                                      WasmlineArtifactFormat artifactFormat);

        /** Releases an artifact and its associated sessions. */
        static void releaseModule(const std::string& key);

        /** Invokes the Core Wasmline entry point and returns its encoded result. */
        static std::string invokeInbound(const std::string& key, const char* action, size_t actionLen, const char* data, size_t dataLen);

        /** Invokes a Core Wasm export with raw values. */
        static InvocationResult invokeRaw(const std::string& key, std::string_view exportName, const std::vector<RawValue>& arguments);

        /** Returns the reflected exports of a cached Core Wasm artifact. */
        static std::vector<RawExportDefinition> describeRawModule(const std::string& artifactKey);

        /** Creates one isolated raw Core Wasm session from a cached artifact. */
        static InvocationResult instantiateRawModule(const std::string& artifactKey, const std::string& sessionKey,
                                                     const std::vector<RawImportDefinition>& imports, RawImportCallback callback,
                                                     RawImportBufferFree bufferFree, void* callbackUser,
                                                     RawImportUserFinalizer userFinalizer, std::string memoryExportName);

        /** Invokes an export on one explicitly instantiated raw session. */
        static InvocationResult invokeRawInstance(const std::string& sessionKey, std::string_view exportName,
                                                  const std::vector<RawValue>& arguments);

        /** Reads bytes from an explicitly instantiated raw session into caller-owned storage. */
        static InvocationResult readRawMemory(const std::string& sessionKey, uint64_t offset, uint8_t* destination, uint64_t length);

        /** Writes bytes into an explicitly instantiated raw session. */
        static InvocationResult writeRawMemory(const std::string& sessionKey, uint64_t offset, const uint8_t* bytes, uint64_t length);

        /** Returns raw session memory size in bytes or pages. */
        static InvocationResult rawMemorySize(const std::string& sessionKey, bool pages);

        /** Grows raw session memory and returns the previous page count. */
        static InvocationResult growRawMemory(const std::string& sessionKey, uint64_t deltaPages);

        /** Releases one explicitly instantiated raw session. */
        static void releaseRawInstance(const std::string& sessionKey);

        /** Invokes a Component Model export with component values. */
        static InvocationResult invokeComponent(const std::string& key, std::string_view exportName,
                                                const std::vector<ComponentValue>& arguments);

        /** Creates one isolated Component instance from a cached compiled artifact. */
        static bool instantiateComponent(const std::string& artifactKey, const std::string& instanceKey,
                                         std::unique_ptr<ComponentHostHandler> handler);

        /** Invokes an export on one explicitly instantiated Component session. */
        static InvocationResult invokeComponentInstance(const std::string& instanceKey, std::string_view exportName,
                                                        const std::vector<ComponentValue>& arguments);

        /** Drops one owned Component resource associated with an instance. */
        static bool dropComponentResource(const std::string& instanceKey, const ComponentResourceReference& reference);

        /** Creates one owned imported Host resource for a Component instance. */
        static bool createComponentHostResource(const std::string& instanceKey, std::string_view interfaceName,
                                                std::string_view resourceName, uint32_t representation,
                                                ComponentResourceReference* reference);

        /** Releases one explicitly instantiated Component session without releasing its artifact. */
        static void releaseComponentInstance(const std::string& instanceKey);

        /** Sets the host handler and serialization codec for outbound calls. */
        static void setOutboundHandler(const std::string& key, std::string codec, std::unique_ptr<OutboundHandler> handler);

        /** Installs a typed host handler before a new Component session is initialized. */
        static bool setComponentHostHandler(const std::string& key, std::unique_ptr<ComponentHostHandler> handler);
    };
} // namespace wasmline
