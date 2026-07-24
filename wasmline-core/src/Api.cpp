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
#include <optional>

namespace wasmline {
    namespace {
        bool hasSuffixIgnoreCase(const std::string& value, const std::string& suffix) {
            if (value.size() < suffix.size()) return false;
            return std::equal(suffix.rbegin(), suffix.rend(), value.rbegin(), [](char lhs, char rhs) {
                return std::tolower(static_cast<unsigned char>(lhs)) == std::tolower(static_cast<unsigned char>(rhs));
            });
        }

        std::optional<bool> pulleyModeForArtifact(const std::string& path) {
            if (hasSuffixIgnoreCase(path, ".pwasm")) return true;
            if (hasSuffixIgnoreCase(path, ".cwasm")) return false;
            return std::nullopt;
        }
    } // namespace

    // Static member initialization
    std::unordered_map<std::string, Session*> Api::sessionCache;
    std::shared_mutex Api::sessionMutex;

    void Api::initEngine() {
        Engine::getInstance().init(true);
    }

    void Api::warmupEngine(bool usePulley) {
        auto& engine = Engine::getInstance();
        if (engine.isInitialized() && engine.isPulley() == usePulley) {
            return;
        }

        if (engine.isInitialized()) {
            releaseEngine();
        }

        engine.init(usePulley);
    }

    bool Api::supportsAot() {
#ifdef WASMTIME_FEATURE_COMPILER
        return true;
#else
        return false;
#endif
    }

    void Api::releaseEngine() {
        // 1. Release all sessions first
        {
            std::unique_lock<std::shared_mutex> lock(sessionMutex);
            for (auto& pair : sessionCache) {
                delete pair.second;
            }
            sessionCache.clear();
        }

        // 2. Release all modules
        Module::getInstance().clear();

        // 3. Release engine
        Engine::getInstance().release();
    }

    bool Api::loadModule(const std::string& key, const std::string& path) {
        ensureEngineForArtifact(path);
        auto* mod = Module::getInstance().load(key, path);
        return (mod != nullptr);
    }

    bool Api::loadModuleUnsafe(const std::string& key, const std::string& path) {
        ensureEngineForArtifact(path);
        auto* mod = Module::getInstance().loadUnsafe(key, path);
        return (mod != nullptr);
    }

    void Api::ensureEngineForArtifact(const std::string& path) {
        auto desiredPulleyMode = pulleyModeForArtifact(path);
        auto& engine = Engine::getInstance();

        if (!desiredPulleyMode.has_value()) {
            if (!engine.isInitialized()) {
                engine.init(true);
            }
            return;
        }

        if (!engine.isInitialized()) {
            engine.init(*desiredPulleyMode);
            return;
        }

        if (engine.isPulley() == *desiredPulleyMode) {
            return;
        }

        LOGI("[Wasmtime] Api --> Reinitializing engine for %s artifact: %s", *desiredPulleyMode ? "pwasm" : "cwasm", path.c_str());
        releaseEngine();
        engine.init(*desiredPulleyMode);
    }

    void Api::releaseModule(const std::string& key) {
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

    // Registers the outbound handler for the target session.
    void Api::setOutboundHandler(const std::string& key, std::unique_ptr<OutboundHandler> handler) {
        Session* session = getOrCreateSession(key);
        if (session) session->setOutboundHandler(std::move(handler));
    }

    // Core execution logic
    std::string Api::invokeInbound(const std::string& key, const char* action, size_t actionLen, const char* data, size_t dataLen) {
        Session* session = getOrCreateSession(key);
        if (!session) {
            LOGE("[Wasmtime] Api --> Call failed, session could not be created for %s", key.c_str());
            return "";
        }
        // Delegate the execution to the Session object
        return session->invokeInbound(action, actionLen, data, dataLen);
    }

    // Helper: Session Management
    Session* Api::getOrCreateSession(const std::string& key) {
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
        wasm_engine_t* engine = Engine::getInstance().getEngine();
        wasmtime_module_t* module = Module::getInstance().get(key);

        if (!engine || !module) {
            LOGE("[Wasmtime] Api --> Cannot create session. Engine or Module is null for %s", key.c_str());
            return nullptr;
        }

        // Create new Session
        auto* session = new Session(engine, module, key);

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
} // namespace wasmline