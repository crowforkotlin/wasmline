/**
 * Implements native Wasmline runtime coordination.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */

#include "wasmline/internal/runtime/NativeRuntime.h"

#include <mutex>
#include <optional>

#include "wasmline/internal/logging/NativeLogger.h"
#include "wasmline/protocol/WasmlineProtocol.h"
#include "wasmline/runtime/Component.h"
#include "wasmline/runtime/ComponentHostHandler.h"
#include "wasmline/runtime/Engine.h"
#include "wasmline/runtime/Module.h"
#include "wasmline/runtime/OutboundHandler.h"
#include "wasmline/runtime/RawModuleSession.h"
#include "wasmline/runtime/Session.h"

namespace wasmline {
    namespace {
        std::optional<bool> pulleyModeForArtifact(WasmlineArtifactFormat format) {
            switch (format) {
            case WasmlineArtifactFormat::RAW_WASM:
                return std::nullopt;
            case WasmlineArtifactFormat::CWASM:
                return false;
            case WasmlineArtifactFormat::PWASM:
                return true;
            }
            return std::nullopt;
        }

        bool rejectsRawNativeArtifact(WasmlineArtifactFormat format, const char* kind, const std::string& path) {
            if (format != WasmlineArtifactFormat::RAW_WASM) return false;
            LOGE("[Wasmtime] NativeRuntime --> Raw %s Wasm is not accepted on native. Precompile to CWASM/PWASM: %s", kind, path.c_str());
            return true;
        }

        bool supportsPulley() {
#ifdef WASMTIME_FEATURE_PULLEY
            return true;
#else
            return false;
#endif
        }

        bool supportsCranelift() {
#ifdef WASMTIME_FEATURE_CRANELIFT
            return true;
#else
            return false;
#endif
        }
    } // namespace

    NativeRuntime::NativeRuntime()
        : serviceSessions_(lifecycleMutex_), rawSessions_(lifecycleMutex_), componentSessions_(lifecycleMutex_) {}

    NativeRuntime& NativeRuntime::instance() {
        static NativeRuntime runtime;
        return runtime;
    }

    bool NativeRuntime::warmup(bool usePulley) {
        std::unique_lock<std::shared_mutex> lock(lifecycleMutex_);
        if ((usePulley && !supportsPulley()) || (!usePulley && !supportsCranelift())) return false;
        auto& engine = Engine::getInstance();
        if (engine.isInitialized() && engine.isPulley() == usePulley) return true;
        if (engine.isInitialized() && hasLoadedArtifacts()) return false;
        engine.release();
        engine.init(usePulley);
        return engine.isInitialized() && engine.isPulley() == usePulley;
    }

    void NativeRuntime::shutdown() {
        std::vector<std::shared_ptr<Session>> serviceSessions;
        std::vector<std::shared_ptr<RawModuleSession>> rawSessions;
        std::vector<std::shared_ptr<ComponentSession>> componentSessions;
        {
            std::unique_lock<std::shared_mutex> lock(lifecycleMutex_);
            serviceSessions = serviceSessions_.detachAll();
            rawSessions = rawSessions_.detachAll();
            componentSessions = componentSessions_.detachAll();
            Module::getInstance().clear();
            Component::getInstance().clear();
            Engine::getInstance().release();
        }
    }

    bool NativeRuntime::loadModule(const std::string& key, const std::string& path, WasmlineArtifactFormat format, bool unsafe) {
        if (rejectsRawNativeArtifact(format, "Core", path)) return false;
        {
            std::shared_lock<std::shared_mutex> lock(lifecycleMutex_);
            if (isEngineReadyForArtifact(format)) {
                return (unsafe ? Module::getInstance().loadUnsafe(key, path, format) : Module::getInstance().load(key, path, format)) !=
                       nullptr;
            }
        }
        std::unique_lock<std::shared_mutex> lock(lifecycleMutex_);
        if (!ensureEngineForArtifact(format, path)) return false;
        return (unsafe ? Module::getInstance().loadUnsafe(key, path, format) : Module::getInstance().load(key, path, format)) != nullptr;
    }

    bool NativeRuntime::loadComponent(const std::string& key, const std::string& path, WasmlineArtifactFormat format, bool unsafe) {
        if (rejectsRawNativeArtifact(format, "Component", path)) return false;
        {
            std::shared_lock<std::shared_mutex> lock(lifecycleMutex_);
            if (isEngineReadyForArtifact(format)) {
                return (unsafe ? Component::getInstance().loadUnsafe(key, path, format)
                               : Component::getInstance().load(key, path, format)) != nullptr;
            }
        }
        std::unique_lock<std::shared_mutex> lock(lifecycleMutex_);
        if (!ensureEngineForArtifact(format, path)) return false;
        return (unsafe ? Component::getInstance().loadUnsafe(key, path, format) : Component::getInstance().load(key, path, format)) !=
               nullptr;
    }

    void NativeRuntime::releaseArtifact(const std::string& key) {
        std::vector<std::shared_ptr<Session>> serviceSessions;
        std::vector<std::shared_ptr<RawModuleSession>> rawSessions;
        std::vector<std::shared_ptr<ComponentSession>> componentSessions;
        {
            std::unique_lock<std::shared_mutex> lock(lifecycleMutex_);
            serviceSessions = serviceSessions_.detachArtifact(key);
            rawSessions = rawSessions_.detachArtifact(key);
            componentSessions = componentSessions_.detachArtifact(key);
            Module::getInstance().release(key);
            Component::getInstance().release(key);
        }
    }

    void NativeRuntime::setOutboundHandler(const std::string& key, std::string codec, std::unique_ptr<OutboundHandler> handler) {
        bool isComponent = false;
        {
            std::shared_lock<std::shared_mutex> lock(lifecycleMutex_);
            isComponent = Component::getInstance().get(key) != nullptr;
        }
        if (isComponent) {
            componentSessions_.setOutboundHandler(key, std::move(codec), std::move(handler));
        } else {
            serviceSessions_.setOutboundHandler(key, std::move(handler));
        }
    }

    bool NativeRuntime::setComponentHostHandler(const std::string& key, std::unique_ptr<ComponentHostHandler> handler) {
        return componentSessions_.setHostHandler(key, std::move(handler));
    }

    std::string NativeRuntime::invokeInbound(const std::string& key, const char* action, size_t actionLen, const char* data,
                                             size_t dataLen) {
        const auto session = serviceSessions_.getOrCreate(key);
        if (!session) {
            return WasmlineResponseCodec::failure(WasmlineErrorCode::ENGINE_NOT_INITIALIZED, "Wasmline session could not be created.");
        }
        return session->invokeInbound(action, actionLen, data, dataLen);
    }

    InvocationResult NativeRuntime::invokeRaw(const std::string& key, std::string_view exportName, const std::vector<RawValue>& arguments) {
        return rawSessions_.invoke(key, exportName, arguments);
    }

    std::vector<RawExportDefinition> NativeRuntime::describeRawModule(const std::string& artifactKey) {
        std::shared_lock<std::shared_mutex> lock(lifecycleMutex_);
        return RawModuleSession::describe(Module::getInstance().get(artifactKey));
    }

    InvocationResult NativeRuntime::instantiateRawModule(const std::string& artifactKey, const std::string& sessionKey,
                                                         const std::vector<RawImportDefinition>& imports, RawImportCallback callback,
                                                         RawImportBufferFree bufferFree, void* callbackUser,
                                                         RawImportUserFinalizer userFinalizer, std::string memoryExportName) {
        return rawSessions_.instantiate(artifactKey, sessionKey, imports, callback, bufferFree, callbackUser, userFinalizer,
                                        std::move(memoryExportName));
    }

    InvocationResult NativeRuntime::invokeRawInstance(const std::string& sessionKey, std::string_view exportName,
                                                      const std::vector<RawValue>& arguments) {
        return rawSessions_.invokeInstance(sessionKey, exportName, arguments);
    }

    InvocationResult NativeRuntime::readRawMemory(const std::string& sessionKey, uint64_t offset, uint8_t* destination, uint64_t length) {
        return rawSessions_.readMemory(sessionKey, offset, destination, length);
    }

    InvocationResult NativeRuntime::writeRawMemory(const std::string& sessionKey, uint64_t offset, const uint8_t* bytes, uint64_t length) {
        return rawSessions_.writeMemory(sessionKey, offset, bytes, length);
    }

    InvocationResult NativeRuntime::rawMemorySize(const std::string& sessionKey, bool pages) {
        return rawSessions_.memorySize(sessionKey, pages);
    }

    InvocationResult NativeRuntime::growRawMemory(const std::string& sessionKey, uint64_t deltaPages) {
        return rawSessions_.growMemory(sessionKey, deltaPages);
    }

    void NativeRuntime::releaseRawInstance(const std::string& sessionKey) {
        rawSessions_.release(sessionKey);
    }

    InvocationResult NativeRuntime::invokeComponent(const std::string& key, std::string_view exportName,
                                                    const std::vector<ComponentValue>& arguments) {
        return componentSessions_.invoke(key, exportName, arguments);
    }

    bool NativeRuntime::instantiateComponent(const std::string& artifactKey, const std::string& instanceKey,
                                             std::unique_ptr<ComponentHostHandler> handler) {
        return componentSessions_.instantiate(artifactKey, instanceKey, std::move(handler));
    }

    InvocationResult NativeRuntime::invokeComponentInstance(const std::string& instanceKey, std::string_view exportName,
                                                            const std::vector<ComponentValue>& arguments) {
        return componentSessions_.invokeInstance(instanceKey, exportName, arguments);
    }

    bool NativeRuntime::dropComponentResource(const std::string& instanceKey, const ComponentResourceReference& reference) {
        return componentSessions_.dropResource(instanceKey, reference);
    }

    bool NativeRuntime::createComponentHostResource(const std::string& instanceKey, std::string_view interfaceName,
                                                    std::string_view resourceName, uint32_t representation,
                                                    ComponentResourceReference* reference) {
        return componentSessions_.createHostResource(instanceKey, interfaceName, resourceName, representation, reference);
    }

    void NativeRuntime::releaseComponentInstance(const std::string& instanceKey) {
        componentSessions_.release(instanceKey);
    }

    bool NativeRuntime::ensureEngineForArtifact(WasmlineArtifactFormat format, const std::string& path) {
        const auto desiredPulleyMode = pulleyModeForArtifact(format);
        auto& engine = Engine::getInstance();
        if (!desiredPulleyMode.has_value()) {
            if (!engine.isInitialized()) engine.init(true);
            return engine.isInitialized();
        }
        if ((*desiredPulleyMode && !supportsPulley()) || (!*desiredPulleyMode && !supportsCranelift())) return false;
        if (!engine.isInitialized()) {
            engine.init(*desiredPulleyMode);
            return engine.isInitialized() && engine.isPulley() == *desiredPulleyMode;
        }
        if (engine.isPulley() == *desiredPulleyMode) return true;
        if (hasLoadedArtifacts()) return false;
        LOGI("[Wasmtime] NativeRuntime --> Selecting engine for artifact: %s", path.c_str());
        engine.release();
        engine.init(*desiredPulleyMode);
        return engine.isInitialized() && engine.isPulley() == *desiredPulleyMode;
    }

    bool NativeRuntime::isEngineReadyForArtifact(WasmlineArtifactFormat format) {
        const auto desiredPulleyMode = pulleyModeForArtifact(format);
        auto& engine = Engine::getInstance();
        if (!desiredPulleyMode.has_value()) return engine.isInitialized();
        if ((*desiredPulleyMode && !supportsPulley()) || (!*desiredPulleyMode && !supportsCranelift())) return false;
        return engine.isInitialized() && engine.isPulley() == *desiredPulleyMode;
    }

    bool NativeRuntime::hasLoadedArtifacts() const {
        return !Module::getInstance().empty() || !Component::getInstance().empty();
    }
} // namespace wasmline
