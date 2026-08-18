/**
 * Defines the native Wasmline API facade.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <shared_mutex>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "wasmline/invocation/InvocationResult.h"
#include "wasmline/runtime/WasmlineArtifactFormat.h"

namespace wasmline {
    class ComponentSession;
    class ComponentHostHandler;
    class OutboundHandler;
    class RawModuleSession;
    class Session;

    /** Provides the native Wasmline API facade. */
    class Api {
    public:
        /** Initializes the selected engine without invalidating loaded artifacts. */
        static bool warmupEngine(bool usePulley);

        /** Returns the exact linked Wasmtime version. */
        static const char* wasmtimeVersion();

        /** Returns whether the linked runtime supports Cranelift artifacts. */
        static bool supportsCranelift();

        /** Returns whether the linked runtime supports Pulley artifacts. */
        static bool supportsPulley();

        /** Releases the global engine, artifacts, and sessions. */
        static void releaseEngine();

        /** Converts a stable native bridge code to a physical artifact format. */
        static bool tryArtifactFormatFromCode(int32_t formatCode, WasmlineArtifactFormat* format);

        /** Loads a Core Wasm artifact with an explicit physical format. */
        static bool loadModule(const std::string& key, const std::string& path, WasmlineArtifactFormat artifactFormat);

        /** Loads a Core Wasm artifact with an explicit format without cache synchronization. */
        static bool loadModuleUnsafe(const std::string& key, const std::string& path, WasmlineArtifactFormat artifactFormat);

        /** Loads a Component Model artifact with an explicit physical format. */
        static bool loadComponent(const std::string& key, const std::string& path, WasmlineArtifactFormat artifactFormat);

        /** Loads a Component Model artifact with an explicit format without cache synchronization. */
        static bool loadComponentUnsafe(const std::string& key, const std::string& path, WasmlineArtifactFormat artifactFormat);

        /** Releases an artifact and its associated sessions. */
        static void releaseModule(const std::string& key);

        /** Invokes the Core Wasmline entry point.
         *
         * @param key Artifact identifier.
         * @param action Action name.
         * @param actionLen Action name length.
         * @param data Input data.
         * @param dataLen Input data length.
         * @return Encoded invocation result.
         */
        static std::string invokeInbound(const std::string& key, const char* action, size_t actionLen, const char* data, size_t dataLen);

        /** Invokes a Core Wasm export with raw values. */
        static InvocationResult invokeRaw(const std::string& key, std::string_view exportName, const std::vector<RawValue>& arguments);

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

    private:
        static bool ensureEngineForArtifact(WasmlineArtifactFormat artifactFormat, const std::string& path);

        static bool isEngineReadyForArtifact(WasmlineArtifactFormat artifactFormat);

        static bool hasLoadedArtifacts();

        /** Returns the cached Core Wasm session or creates it. */
        static Session* getOrCreateSession(const std::string& key);

        static RawModuleSession* getOrCreateRawSession(const std::string& key);

        static std::shared_ptr<ComponentSession> getOrCreateComponentSession(const std::string& key);

        static std::unordered_map<std::string, std::unique_ptr<Session>> sessionCache;

        static std::unordered_map<std::string, std::unique_ptr<RawModuleSession>> rawSessionCache;

        static std::unordered_map<std::string, std::shared_ptr<ComponentSession>> componentSessionCache;

        static std::shared_mutex sessionMutex;

        static std::shared_mutex lifecycleMutex;
    };
} // namespace wasmline
