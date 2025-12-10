/**
 * WasmApi.cpp
 * Implementation of the Wasm API Facade.
 * Coordinates calls between Engine, Module, and Session.
 *
 * 2025-12-03
 * @author crowforkotlin
 */

#include "WasmApi.h"
#include "WasmEngine.h"
#include "WasmModule.h"
#include "WasmLogger.h"

// Static member initialization
std::unordered_map<std::string, WasmSession*> WasmApi::sessionCache;
std::shared_mutex WasmApi::sessionMutex;

void WasmApi::initEngine() {
    WasmEngine::getInstance().init();
}

void WasmApi::releaseEngine() {
    // 1. Release all sessions first
    {
        std::unique_lock<std::shared_mutex> lock(sessionMutex);
        for (auto& pair : sessionCache) {
            delete pair.second;
        }
        sessionCache.clear();
    }

    // 2. Release all modules
    WasmModule::getInstance().clear();

    // 3. Release engine
    WasmEngine::getInstance().release();
}

bool WasmApi::loadModule(const std::string& key, const std::string& path, bool isJit) {
    auto* mod = WasmModule::getInstance().load(key, path, isJit);
    return (mod != nullptr);
}

bool WasmApi::loadModuleUnsafe(const std::string& key, const std::string& path, bool isJit) {
    auto* mod = WasmModule::getInstance().loadUnsafe(key, path, isJit);
    return (mod != nullptr);
}

bool WasmApi::saveModuleCache(const std::string& key, const std::string& path) {
    return WasmModule::getInstance().serialize(key, path);
}

bool WasmApi::saveModuleCacheUnsafe(const std::string& key, const std::string& path) {
    return WasmModule::getInstance().serializeUnsafe(key, path);
}

void WasmApi::releaseModule(const std::string& key) {
    // 1. Remove associated session if exists
    {
        std::unique_lock<std::shared_mutex> lock(sessionMutex);
        auto it = sessionCache.find(key);
        if (it != sessionCache.end()) {
            delete it->second;
            sessionCache.erase(it);
        }
    }
    // 2. Release module from Module Manager
    WasmModule::getInstance().release(key);
}

// Core execution logic
std::string WasmApi::call(const std::string& key, const std::string& action, const std::string& data) {
    WasmSession* session = getOrCreateSession(key);
    if (!session) {
        LOGE("[Wasmtime] WasmApi --> Call failed, session could not be created for %s", key.c_str());
        return "";
    }
    // Delegate the execution to the Session object
    return session->call(action.c_str(), action.size(), data.c_str(), data.size());
}

// Helper: Session Management
WasmSession* WasmApi::getOrCreateSession(const std::string& key) {
    // 1. Fast Path: Check cache (Read Lock)
    {
        std::shared_lock<std::shared_mutex> lock(sessionMutex);
        auto it = sessionCache.find(key);
        if (it != sessionCache.end()) return it->second;
    }

    // 2. Create Path: Acquire Write Lock
    std::unique_lock<std::shared_mutex> lock(sessionMutex);
    
    // Double check
    if (sessionCache.count(key)) return sessionCache[key];

    // Get prerequisites
    wasm_engine_t* engine = WasmEngine::getInstance().getEngine();
    wasmtime_module_t* module = WasmModule::getInstance().get(key);

    if (!engine || !module) {
        LOGE("[Wasmtime] WasmApi --> Cannot create session. Engine or Module is null for %s", key.c_str());
        return nullptr;
    }

    // Create new Session
    auto* session = new WasmSession(engine, module, key);
    
    // Initialize Session (Register hosts, WASI, Instance)
    // Note: session->initialize() is thread-safe internally
    if (!session->initialize()) {
        LOGE("[Wasmtime] WasmApi --> Failed to initialize session for %s", key.c_str());
        delete session;
        return nullptr;
    }

    sessionCache[key] = session;
    return session;
}