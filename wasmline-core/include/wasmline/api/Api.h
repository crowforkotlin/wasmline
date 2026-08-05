/**
 * Defines the native Wasmline API facade.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#pragma once

#include <cstddef>
#include <memory>
#include <shared_mutex>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "wasmline/invocation/InvocationResult.h"

namespace wasmline {
    class ComponentSession;
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

        /** Releases the global engine, artifacts, and sessions. */
        static void releaseEngine();

        /** Loads a Core Wasm artifact.
         *
         * @param key Artifact identifier.
         * @param path Artifact file path.
         * @return true when the artifact is loaded.
         */
        static bool loadModule(const std::string& key, const std::string& path);

        /** Loads a Core Wasm artifact without cache synchronization. */
        static bool loadModuleUnsafe(const std::string& key, const std::string& path);

        /** Loads a Component Model artifact.
         *
         * @param key Artifact identifier.
         * @param path Component file path.
         * @return true when the artifact is loaded.
         */
        static bool loadComponent(const std::string& key, const std::string& path);

        /** Loads a Component Model artifact without cache synchronization. */
        static bool loadComponentUnsafe(const std::string& key, const std::string& path);

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

    private:
        static void ensureEngineForArtifact(const std::string& path);

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
