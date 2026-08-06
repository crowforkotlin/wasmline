/**
 * Implements the native Wasmline API facade.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "wasmline/api/Api.h"

#include "wasmline/protocol/WasmlineProtocol.h"
#include "wasmline/runtime/Component.h"
#include "wasmline/runtime/ComponentSession.h"
#include "wasmline/runtime/Engine.h"
#include "wasmline/runtime/Module.h"
#include "wasmline/runtime/RawModuleSession.h"
#include "wasmline/runtime/Session.h"
#include "logging/NativeLogger.h"

#include <algorithm>
#include <cctype>
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

        /** Creates or returns a cached session for an artifact. */
        template <typename SessionType, typename ArtifactType, typename CacheType>
        SessionType* createCachedSession(const std::string& key, CacheType& cache, std::shared_mutex& mutex, wasm_engine_t* engine,
                                         ArtifactType* artifact, const char* sessionType) {
            {
                std::shared_lock<std::shared_mutex> lock(mutex);
                const auto cached = cache.find(key);
                if (cached != cache.end()) return cached->second.get();
            }

            std::unique_lock<std::shared_mutex> lock(mutex);
            const auto cached = cache.find(key);
            if (cached != cache.end()) return cached->second.get();

            if (!engine || !artifact) {
                LOGE("[Wasmtime] Api --> Cannot create %s session. Engine or artifact is null for %s", sessionType, key.c_str());
                return nullptr;
            }

            auto session = std::make_unique<SessionType>(engine, artifact, key);
            if (!session->initialize()) {
                LOGE("[Wasmtime] Api --> Failed to initialize %s session for %s", sessionType, key.c_str());
                return nullptr;
            }

            SessionType* result = session.get();
            cache.emplace(key, std::move(session));
            return result;
        }
    } // namespace

    std::unordered_map<std::string, std::unique_ptr<Session>> Api::sessionCache;
    std::unordered_map<std::string, std::unique_ptr<RawModuleSession>> Api::rawSessionCache;
    std::unordered_map<std::string, std::unique_ptr<ComponentSession>> Api::componentSessionCache;
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

    const char* Api::wasmtimeVersion() {
        return WASMTIME_VERSION;
    }

    bool Api::supportsCranelift() {
#ifdef WASMTIME_FEATURE_CRANELIFT
        return true;
#else
        return false;
#endif
    }

    bool Api::supportsPulley() {
#ifdef WASMTIME_FEATURE_PULLEY
        return true;
#else
        return false;
#endif
    }

    void Api::releaseEngine() {
        {
            std::unique_lock<std::shared_mutex> lock(sessionMutex);
            sessionCache.clear();
            rawSessionCache.clear();
            componentSessionCache.clear();
        }

        Module::getInstance().clear();
        Component::getInstance().clear();

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

    bool Api::loadComponent(const std::string& key, const std::string& path) {
        ensureEngineForArtifact(path);
        auto* component = Component::getInstance().load(key, path);
        return component != nullptr;
    }

    bool Api::loadComponentUnsafe(const std::string& key, const std::string& path) {
        ensureEngineForArtifact(path);
        auto* component = Component::getInstance().loadUnsafe(key, path);
        return component != nullptr;
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
        {
            std::unique_lock<std::shared_mutex> lock(sessionMutex);
            sessionCache.erase(key);
            rawSessionCache.erase(key);
            componentSessionCache.erase(key);
        }
        Module::getInstance().release(key);
        Component::getInstance().release(key);
    }

    void Api::setOutboundHandler(const std::string& key, std::string codec, std::unique_ptr<OutboundHandler> handler) {
        if (Component::getInstance().get(key)) {
            ComponentSession* componentSession = getOrCreateComponentSession(key);
            if (componentSession) componentSession->setOutboundHandler(std::move(handler), std::move(codec));
            return;
        }
        Session* session = getOrCreateSession(key);
        if (session) session->setOutboundHandler(std::move(handler));
    }

    std::string Api::invokeInbound(const std::string& key, const char* action, size_t actionLen, const char* data, size_t dataLen) {
        Session* session = getOrCreateSession(key);
        if (!session) {
            LOGE("[Wasmtime] Api --> Call failed, session could not be created for %s", key.c_str());
            return WasmlineResponseCodec::failure(WasmlineErrorCode::ENGINE_NOT_INITIALIZED, "Wasmline session could not be created.");
        }
        return session->invokeInbound(action, actionLen, data, dataLen);
    }

    InvocationResult Api::invokeRaw(const std::string& key, std::string_view exportName, const std::vector<RawValue>& arguments) {
        RawModuleSession* session = getOrCreateRawSession(key);
        if (!session) {
            return InvocationResult::failure(WasmlineErrorCode::ENGINE_NOT_INITIALIZED, "Raw module session could not be created.");
        }
        return session->invoke(exportName, arguments);
    }

    InvocationResult Api::invokeComponent(const std::string& key, std::string_view exportName,
                                          const std::vector<ComponentValue>& arguments) {
        ComponentSession* session = getOrCreateComponentSession(key);
        if (!session) {
            return InvocationResult::failure(WasmlineErrorCode::ENGINE_NOT_INITIALIZED, "Component session could not be created.");
        }
        return session->invoke(exportName, arguments);
    }

    Session* Api::getOrCreateSession(const std::string& key) {
        wasm_engine_t* engine = Engine::getInstance().getEngine();
        wasmtime_module_t* module = Module::getInstance().get(key);
        return createCachedSession<Session>(key, sessionCache, sessionMutex, engine, module, "core");
    }

    RawModuleSession* Api::getOrCreateRawSession(const std::string& key) {
        wasm_engine_t* engine = Engine::getInstance().getEngine();
        wasmtime_module_t* module = Module::getInstance().get(key);
        return createCachedSession<RawModuleSession>(key, rawSessionCache, sessionMutex, engine, module, "raw");
    }

    ComponentSession* Api::getOrCreateComponentSession(const std::string& key) {
        wasm_engine_t* engine = Engine::getInstance().getEngine();
        wasmtime_component_t* component = Component::getInstance().get(key);
        return createCachedSession<ComponentSession>(key, componentSessionCache, sessionMutex, engine, component, "component");
    }
} // namespace wasmline
