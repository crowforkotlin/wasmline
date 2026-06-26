/**
 * Api.h
 * Unified API Facade for Wasm operations.
 * Acts as a bridge between JNI and the underlying Engine/Module/Session components.
 * Manages the lifecycle of Session instances.
 *
 * 2025-12-03
 * @author crowforkotlin
 */

#pragma once

#include <string>
#include <memory>
#include <vector>
#include <unordered_map>
#include <shared_mutex>
#include "Session.h"

namespace wasmline {
    class Api {
    public:
        /**
         * Initializes the Global Engine.
         */
        static void initEngine();

        /**
         * Eagerly initializes the global engine for a specific backend.
         */
        static void warmupEngine(bool usePulley);

        /**
         * Returns true if this build includes the Cranelift compiler (AOT support).
         * Determined at compile time by the WASMTIME_FEATURE_COMPILER macro.
         */
        static bool supportsAot();

        /**
         * Releases the Global Engine and all resources.
         */
        static void releaseEngine();

        /**
         * Loads a precompiled module artifact via Module.
         */
        static bool loadModule(const std::string& key, const std::string& path);

        /**
         * Loads a precompiled module artifact via Module. (Not thread-safe.)
         */
        static bool loadModuleUnsafe(const std::string& key, const std::string& path);


        /**
         * Releases a module and its associated sessions.
         */
        static void releaseModule(const std::string& key);

        /**
         * Executes a function invokeInbound on a specific module.
         * Manages Session creation and caching internally.
         *
         * @param key Module identifier
         * @param action Action name
         * @param data Input binary data
         * @return Output binary data
         */
        static std::string invokeInbound(const std::string &key, const char* action, size_t actionLen, const char* data, size_t dataLen);

        static void setOutboundHandler(const std::string& key, std::unique_ptr<OutboundHandler> handler);

    private:
        static void ensureEngineForArtifact(const std::string &path);

        /**
         * Internal helper to get or create a session.
         * Thread-safe.
         */
        static Session* getOrCreateSession(const std::string& key);

        // Map to cache active sessions: Key -> Session*
        // Note: Api manages sessions, while Module manages modules.
        static std::unordered_map<std::string, Session*> sessionCache;

        // Mutex for session cache safety
        static std::shared_mutex sessionMutex;
    };
}