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
        /** Initializes the global Wasmtime engine. */
        static void initEngine();

        /** Initializes the global engine for the selected backend. */
        static void warmupEngine(bool usePulley);

        /** Returns whether this build includes the Wasmtime compiler. */
        static bool supportsAot();

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

        /** Legacy Core artifact load entrypoint. It fails because native loading requires an explicit format.
         *
         * @param key Artifact identifier.
         * @param path Artifact file path.
         * @return true when the artifact is loaded.
         */
        static bool loadModule(const std::string& key, const std::string& path);

        /** Loads a Core Wasm artifact with an explicit physical format. */
        static bool loadModule(const std::string& key, const std::string& path, WasmlineArtifactFormat artifactFormat);

        /** Legacy Core artifact load entrypoint without cache synchronization. It fails without an explicit format. */
        static bool loadModuleUnsafe(const std::string& key, const std::string& path);

        /** Loads a Core Wasm artifact with an explicit format without cache synchronization. */
        static bool loadModuleUnsafe(const std::string& key, const std::string& path, WasmlineArtifactFormat artifactFormat);

        /** Legacy Component artifact load entrypoint. It fails because native loading requires an explicit format.
         *
         * @param key Artifact identifier.
         * @param path Component file path.
         * @return true when the artifact is loaded.
         */
        static bool loadComponent(const std::string& key, const std::string& path);

        /** Loads a Component Model artifact with an explicit physical format. */
        static bool loadComponent(const std::string& key, const std::string& path, WasmlineArtifactFormat artifactFormat);

        /** Legacy Component artifact load entrypoint without cache synchronization. It fails without an explicit format. */
        static bool loadComponentUnsafe(const std::string& key, const std::string& path);

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

        /** Sets the host handler and serialization codec for outbound calls. */
        static void setOutboundHandler(const std::string& key, std::string codec, std::unique_ptr<OutboundHandler> handler);

        /** Installs a typed host handler before a new Component session is initialized. */
        static bool setComponentHostHandler(const std::string& key, std::unique_ptr<ComponentHostHandler> handler);

    private:
        static void ensureEngineForArtifact(WasmlineArtifactFormat artifactFormat, const std::string& path);

        /** Returns the cached Core Wasm session or creates it. */
        static Session* getOrCreateSession(const std::string& key);

        static RawModuleSession* getOrCreateRawSession(const std::string& key);

        static ComponentSession* getOrCreateComponentSession(const std::string& key);

        static std::unordered_map<std::string, std::unique_ptr<Session>> sessionCache;

        static std::unordered_map<std::string, std::unique_ptr<RawModuleSession>> rawSessionCache;

        static std::unordered_map<std::string, std::unique_ptr<ComponentSession>> componentSessionCache;

        static std::shared_mutex sessionMutex;
    };
} // namespace wasmline
