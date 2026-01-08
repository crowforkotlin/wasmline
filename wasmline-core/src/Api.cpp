/**
 * Api.native
 * Implementation of the Wasm API Facade.
 * Coordinates calls between Engine, Module, and Session.
 *
 * 2025-12-03
 * @author crowforkotlin
 */

#include "Api.h"
#include "Engine.h"
#include "Module.h"
#include "Logger.h"

namespace wasmline {
    // Static member initialization
    std::unordered_map<std::string, Session *> Api::sessionCache;
    std::shared_mutex Api::sessionMutex;

    void Api::initEngine() {
        Engine::getInstance().init();
    }

    void Api::releaseEngine() {
        // 1. Release all sessions first
        {
            std::unique_lock<std::shared_mutex> lock(sessionMutex);
            for (auto &pair: sessionCache) {
                delete pair.second;
            }
            sessionCache.clear();
        }

        // 2. Release all modules
        Module::getInstance().clear();

        // 3. Release engine
        Engine::getInstance().release();
    }

    bool Api::loadModule(const std::string &key, const std::string &path, bool isJit) {
        auto *mod = Module::getInstance().load(key, path, isJit);
        return (mod != nullptr);
    }

    bool Api::loadModuleUnsafe(const std::string &key, const std::string &path, bool isJit) {
        auto *mod = Module::getInstance().loadUnsafe(key, path, isJit);
        return (mod != nullptr);
    }

    bool Api::saveModuleCache(const std::string &key, const std::string &path) {
        return Module::getInstance().serialize(key, path);
    }

    bool Api::saveModuleCacheUnsafe(const std::string &key, const std::string &path) {
        return Module::getInstance().serializeUnsafe(key, path);
    }

    void Api::releaseModule(const std::string &key) {
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
        Module::getInstance().release(key);
    }

    // 增加注册方法
    void Api::setOutboundHandler(const std::string &key, std::unique_ptr<OutboundHandler> handler) {
        Session *session = getOrCreateSession(key);
        if (session) session->setOutboundHandler(std::move(handler));
    }

    // Core execution logic
    std::string Api::invokeInbound(const std::string &key, const char* action, size_t actionLen, const char* data, size_t dataLen) {
        Session *session = getOrCreateSession(key);
        if (!session) {
            LOGE("[Wasmtime] Api --> Call failed, session could not be created for %s", key.c_str());
            return "";
        }
        // Delegate the execution to the Session object
        return session->invokeInbound(action, actionLen, data, dataLen);
    }

    // Helper: Session Management
    Session *Api::getOrCreateSession(const std::string &key) {
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
        wasm_engine_t *engine = Engine::getInstance().getEngine();
        wasmtime_module_t *module = Module::getInstance().get(key);

        if (!engine || !module) {
            LOGE("[Wasmtime] Api --> Cannot create session. Engine or Module is null for %s", key.c_str());
            return nullptr;
        }

        // Create new Session
        auto *session = new Session(engine, module, key);

        // Initialize Session (Register hosts, WASI, Instance)
        // Note: session->initialize() is thread-safe internally
        if (!session->initialize()) {
            LOGE("[Wasmtime] Api --> Failed to initialize session for %s", key.c_str());
            delete session;
            return nullptr;
        }

        sessionCache[key] = session;
        return session;
    }
}