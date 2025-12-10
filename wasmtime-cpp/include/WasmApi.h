/**
 * WasmApi.h
 * Unified API Facade for Wasm operations.
 * Acts as a bridge between JNI and the underlying Engine/Module/Session components.
 * Manages the lifecycle of WasmSession instances.
 *
 * 2025-12-03
 * @author crowforkotlin
 */

#pragma once

#include <string>
#include <vector>
#include <unordered_map>
#include <shared_mutex>
#include "WasmSession.h"

class WasmApi {
public:
    /**
     * Initializes the Global Engine.
     */
    static void initEngine();

    /**
     * Releases the Global Engine and all resources.
     */
    static void releaseEngine();

    /**
     * Loads a module via WasmModule.
     */
    static bool loadModule(const std::string& key, const std::string& path, bool isJit);

    /**
     * Loads a module via WasmModule. (Not thread-safe.)
     */
    static bool loadModuleUnsafe(const std::string& key, const std::string& path, bool isJit);

    /**
     * Saves a cached module to disk.
     */
    static bool saveModuleCache(const std::string& key, const std::string& path);

    /**
     * Saves a cached module to disk.
     */
    static bool saveModuleCacheUnsafe(const std::string& key, const std::string& path);

    /**
     * Releases a module and its associated sessions.
     */
    static void releaseModule(const std::string& key);

    /**
     * Executes a function call on a specific module.
     * Manages Session creation and caching internally.
     *
     * @param key Module identifier
     * @param action Action name
     * @param data Input binary data
     * @return Output binary data
     */
    static std::string call(const std::string& key, const std::string& action, const std::string& data);

    static void registerHostHandler(const std::string& key, std::unique_ptr<WasmHostHandler> handler);

private:
    /**
     * Internal helper to get or create a session.
     * Thread-safe.
     */
    static WasmSession* getOrCreateSession(const std::string& key);

    // Map to cache active sessions: Key -> Session*
    // Note: WasmApi manages sessions, while WasmModule manages modules.
    static std::unordered_map<std::string, WasmSession*> sessionCache;
    
    // Mutex for session cache safety
    static std::shared_mutex sessionMutex;
};