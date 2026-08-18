/**
 * Implements the native Wasmline API facade.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "wasmline/api/Api.h"

#include "wasmline/protocol/WasmlineProtocol.h"
#include "wasmline/runtime/Component.h"
#include "wasmline/runtime/ComponentHostHandler.h"
#include "wasmline/runtime/ComponentSession.h"
#include "wasmline/runtime/Engine.h"
#include "wasmline/runtime/Module.h"
#include "wasmline/runtime/RawModuleSession.h"
#include "wasmline/runtime/Session.h"
#include "logging/NativeLogger.h"

#include <mutex>
#include <optional>

namespace wasmline {
    namespace {
        std::optional<bool> pulleyModeForArtifact(WasmlineArtifactFormat artifactFormat) {
            switch (artifactFormat) {
            case WasmlineArtifactFormat::RAW_WASM:
                return std::nullopt;
            case WasmlineArtifactFormat::CWASM:
                return false;
            case WasmlineArtifactFormat::PWASM:
                return true;
            }
            return std::nullopt;
        }

        bool rejectsRawNativeArtifact(WasmlineArtifactFormat artifactFormat, const char* artifactKind, const std::string& path) {
            if (artifactFormat != WasmlineArtifactFormat::RAW_WASM) return false;
            LOGE("[Wasmtime] Api --> Raw %s Wasm is not accepted on native. Precompile to CWASM/PWASM: %s", artifactKind, path.c_str());
            return true;
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
    std::unordered_map<std::string, std::shared_ptr<ComponentSession>> Api::componentSessionCache;
    std::shared_mutex Api::sessionMutex;
    std::shared_mutex Api::lifecycleMutex;

    bool Api::warmupEngine(bool usePulley) {
        std::unique_lock<std::shared_mutex> lock(lifecycleMutex);
        if ((usePulley && !supportsPulley()) || (!usePulley && !supportsCranelift())) {
            LOGE("[Wasmtime] Api --> Requested engine is not supported: %s", usePulley ? "pulley" : "cranelift");
            return false;
        }

        auto& engine = Engine::getInstance();
        if (engine.isInitialized() && engine.isPulley() == usePulley) {
            return true;
        }

        if (engine.isInitialized() && hasLoadedArtifacts()) {
            LOGE("[Wasmtime] Api --> Cannot switch to %s while artifacts are loaded.", usePulley ? "pulley" : "cranelift");
            return false;
        }

        engine.release();
        engine.init(usePulley);
        return engine.isInitialized() && engine.isPulley() == usePulley;
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
        std::unique_lock<std::shared_mutex> lifecycleLock(lifecycleMutex);
        std::unordered_map<std::string, std::shared_ptr<ComponentSession>> componentSessions;
        {
            std::unique_lock<std::shared_mutex> lock(sessionMutex);
            sessionCache.clear();
            rawSessionCache.clear();
            componentSessions.swap(componentSessionCache);
        }
        componentSessions.clear();

        Module::getInstance().clear();
        Component::getInstance().clear();

        Engine::getInstance().release();
    }

    bool Api::tryArtifactFormatFromCode(int32_t formatCode, WasmlineArtifactFormat* format) {
        if (!format) return false;
        switch (formatCode) {
        case static_cast<int32_t>(WasmlineArtifactFormat::RAW_WASM):
            *format = WasmlineArtifactFormat::RAW_WASM;
            return true;
        case static_cast<int32_t>(WasmlineArtifactFormat::CWASM):
            *format = WasmlineArtifactFormat::CWASM;
            return true;
        case static_cast<int32_t>(WasmlineArtifactFormat::PWASM):
            *format = WasmlineArtifactFormat::PWASM;
            return true;
        default:
            return false;
        }
    }

    bool Api::loadModule(const std::string& key, const std::string& path, WasmlineArtifactFormat artifactFormat) {
        if (rejectsRawNativeArtifact(artifactFormat, "Core", path)) return false;
        {
            std::shared_lock<std::shared_mutex> lock(lifecycleMutex);
            if (isEngineReadyForArtifact(artifactFormat)) {
                return Module::getInstance().load(key, path, artifactFormat) != nullptr;
            }
        }

        std::unique_lock<std::shared_mutex> lock(lifecycleMutex);
        if (!ensureEngineForArtifact(artifactFormat, path)) return false;
        auto* mod = Module::getInstance().load(key, path, artifactFormat);
        return (mod != nullptr);
    }

    bool Api::loadModuleUnsafe(const std::string& key, const std::string& path, WasmlineArtifactFormat artifactFormat) {
        if (rejectsRawNativeArtifact(artifactFormat, "Core", path)) return false;
        {
            std::shared_lock<std::shared_mutex> lock(lifecycleMutex);
            if (isEngineReadyForArtifact(artifactFormat)) {
                return Module::getInstance().loadUnsafe(key, path, artifactFormat) != nullptr;
            }
        }

        std::unique_lock<std::shared_mutex> lock(lifecycleMutex);
        if (!ensureEngineForArtifact(artifactFormat, path)) return false;
        auto* mod = Module::getInstance().loadUnsafe(key, path, artifactFormat);
        return (mod != nullptr);
    }

    bool Api::loadComponent(const std::string& key, const std::string& path, WasmlineArtifactFormat artifactFormat) {
        if (rejectsRawNativeArtifact(artifactFormat, "Component", path)) return false;
        {
            std::shared_lock<std::shared_mutex> lock(lifecycleMutex);
            if (isEngineReadyForArtifact(artifactFormat)) {
                return Component::getInstance().load(key, path, artifactFormat) != nullptr;
            }
        }

        std::unique_lock<std::shared_mutex> lock(lifecycleMutex);
        if (!ensureEngineForArtifact(artifactFormat, path)) return false;
        auto* component = Component::getInstance().load(key, path, artifactFormat);
        return component != nullptr;
    }

    bool Api::loadComponentUnsafe(const std::string& key, const std::string& path, WasmlineArtifactFormat artifactFormat) {
        if (rejectsRawNativeArtifact(artifactFormat, "Component", path)) return false;
        {
            std::shared_lock<std::shared_mutex> lock(lifecycleMutex);
            if (isEngineReadyForArtifact(artifactFormat)) {
                return Component::getInstance().loadUnsafe(key, path, artifactFormat) != nullptr;
            }
        }

        std::unique_lock<std::shared_mutex> lock(lifecycleMutex);
        if (!ensureEngineForArtifact(artifactFormat, path)) return false;
        auto* component = Component::getInstance().loadUnsafe(key, path, artifactFormat);
        return component != nullptr;
    }

    bool Api::ensureEngineForArtifact(WasmlineArtifactFormat artifactFormat, const std::string& path) {
        auto desiredPulleyMode = pulleyModeForArtifact(artifactFormat);
        auto& engine = Engine::getInstance();

        if (!desiredPulleyMode.has_value()) {
            if (!engine.isInitialized()) {
                engine.init(true);
            }
            return engine.isInitialized();
        }

        if ((*desiredPulleyMode && !supportsPulley()) || (!*desiredPulleyMode && !supportsCranelift())) {
            LOGE("[Wasmtime] Api --> Runtime does not support the engine required by artifact: %s", path.c_str());
            return false;
        }

        if (!engine.isInitialized()) {
            engine.init(*desiredPulleyMode);
            return engine.isInitialized() && engine.isPulley() == *desiredPulleyMode;
        }

        if (engine.isPulley() == *desiredPulleyMode) {
            return true;
        }

        if (hasLoadedArtifacts()) {
            LOGE("[Wasmtime] Api --> Cannot load %s artifact while artifacts for another engine remain loaded: %s",
                 *desiredPulleyMode ? "pwasm" : "cwasm", path.c_str());
            return false;
        }

        LOGI("[Wasmtime] Api --> Selecting engine for %s artifact: %s", *desiredPulleyMode ? "pwasm" : "cwasm", path.c_str());
        engine.release();
        engine.init(*desiredPulleyMode);
        return engine.isInitialized() && engine.isPulley() == *desiredPulleyMode;
    }

    bool Api::isEngineReadyForArtifact(WasmlineArtifactFormat artifactFormat) {
        const auto desiredPulleyMode = pulleyModeForArtifact(artifactFormat);
        auto& engine = Engine::getInstance();
        if (!desiredPulleyMode.has_value()) return engine.isInitialized();
        if ((*desiredPulleyMode && !supportsPulley()) || (!*desiredPulleyMode && !supportsCranelift())) return false;
        return engine.isInitialized() && engine.isPulley() == *desiredPulleyMode;
    }

    bool Api::hasLoadedArtifacts() {
        return !Module::getInstance().empty() || !Component::getInstance().empty();
    }

    void Api::releaseModule(const std::string& key) {
        std::unique_lock<std::shared_mutex> lifecycleLock(lifecycleMutex);
        std::shared_ptr<ComponentSession> componentSession;
        {
            std::unique_lock<std::shared_mutex> lock(sessionMutex);
            sessionCache.erase(key);
            rawSessionCache.erase(key);
            const auto found = componentSessionCache.find(key);
            if (found != componentSessionCache.end()) {
                componentSession = std::move(found->second);
                componentSessionCache.erase(found);
            }
        }
        componentSession.reset();
        Module::getInstance().release(key);
        Component::getInstance().release(key);
    }

    void Api::setOutboundHandler(const std::string& key, std::string codec, std::unique_ptr<OutboundHandler> handler) {
        if (Component::getInstance().get(key)) {
            std::shared_ptr<ComponentSession> componentSession = getOrCreateComponentSession(key);
            if (componentSession) componentSession->setOutboundHandler(std::move(handler), std::move(codec));
            return;
        }
        Session* session = getOrCreateSession(key);
        if (session) session->setOutboundHandler(std::move(handler));
    }

    bool Api::setComponentHostHandler(const std::string& key, std::unique_ptr<ComponentHostHandler> handler) {
        wasm_engine_t* engine = Engine::getInstance().getEngine();
        wasmtime_component_t* component = Component::getInstance().get(key);
        if (!engine || !component) {
            LOGE("[Wasmtime] Api --> Cannot install typed Component host handler for %s", key.c_str());
            return false;
        }

        std::unique_lock<std::shared_mutex> lock(sessionMutex);
        const auto cached = componentSessionCache.find(key);
        if (cached != componentSessionCache.end()) {
            cached->second->setComponentHostHandler(std::move(handler));
            return true;
        }

        auto session = std::make_shared<ComponentSession>(engine, component, key);
        session->setComponentHostHandler(std::move(handler));
        if (!session->initialize()) {
            LOGE("[Wasmtime] Api --> Failed to initialize component session for typed host handler: %s", key.c_str());
            return false;
        }

        componentSessionCache.emplace(key, std::move(session));
        return true;
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
        std::shared_ptr<ComponentSession> session = getOrCreateComponentSession(key);
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

    std::shared_ptr<ComponentSession> Api::getOrCreateComponentSession(const std::string& key) {
        {
            std::shared_lock<std::shared_mutex> lock(sessionMutex);
            const auto cached = componentSessionCache.find(key);
            if (cached != componentSessionCache.end()) return cached->second;
        }
        wasm_engine_t* engine = Engine::getInstance().getEngine();
        wasmtime_component_t* component = Component::getInstance().get(key);
        if (!engine || !component) return nullptr;

        auto session = std::make_shared<ComponentSession>(engine, component, key);
        if (!session->initialize()) return nullptr;

        std::unique_lock<std::shared_mutex> lock(sessionMutex);
        const auto [cached, inserted] = componentSessionCache.emplace(key, session);
        return inserted ? std::move(session) : cached->second;
    }

    bool Api::instantiateComponent(const std::string& artifactKey, const std::string& instanceKey,
                                   std::unique_ptr<ComponentHostHandler> handler) {
        if (artifactKey.empty() || instanceKey.empty() || !handler) return false;
        {
            std::shared_lock<std::shared_mutex> lock(sessionMutex);
            if (componentSessionCache.find(instanceKey) != componentSessionCache.end()) return false;
        }

        wasm_engine_t* engine = Engine::getInstance().getEngine();
        wasmtime_component_t* component = Component::getInstance().get(artifactKey);
        if (!engine || !component) return false;

        auto session = std::make_shared<ComponentSession>(engine, component, instanceKey);
        session->setComponentHostHandler(std::move(handler));
        if (!session->initialize()) return false;

        std::unique_lock<std::shared_mutex> lock(sessionMutex);
        return componentSessionCache.emplace(instanceKey, std::move(session)).second;
    }

    InvocationResult Api::invokeComponentInstance(const std::string& instanceKey, std::string_view exportName,
                                                  const std::vector<ComponentValue>& arguments) {
        std::shared_ptr<ComponentSession> session;
        {
            std::shared_lock<std::shared_mutex> lock(sessionMutex);
            const auto cached = componentSessionCache.find(instanceKey);
            if (cached != componentSessionCache.end()) session = cached->second;
        }
        if (!session) {
            return InvocationResult::failure(WasmlineErrorCode::ENGINE_NOT_INITIALIZED,
                                             "Component instance is not initialized or has been closed.");
        }
        return session->invoke(exportName, arguments);
    }

    bool Api::dropComponentResource(const std::string& instanceKey, const ComponentResourceReference& reference) {
        std::shared_ptr<ComponentSession> session;
        {
            std::shared_lock<std::shared_mutex> lock(sessionMutex);
            const auto cached = componentSessionCache.find(instanceKey);
            if (cached != componentSessionCache.end()) session = cached->second;
        }
        return session && session->dropResource(reference);
    }

    bool Api::createComponentHostResource(const std::string& instanceKey, std::string_view interfaceName, std::string_view resourceName,
                                          uint32_t representation, ComponentResourceReference* reference) {
        std::shared_ptr<ComponentSession> session;
        {
            std::shared_lock<std::shared_mutex> lock(sessionMutex);
            const auto cached = componentSessionCache.find(instanceKey);
            if (cached != componentSessionCache.end()) session = cached->second;
        }
        return session && session->createHostResource(interfaceName, resourceName, representation, reference);
    }

    void Api::releaseComponentInstance(const std::string& instanceKey) {
        std::shared_ptr<ComponentSession> session;
        {
            std::unique_lock<std::shared_mutex> lock(sessionMutex);
            const auto found = componentSessionCache.find(instanceKey);
            if (found != componentSessionCache.end()) {
                session = std::move(found->second);
                componentSessionCache.erase(found);
            }
        }
        session.reset();
    }
} // namespace wasmline
