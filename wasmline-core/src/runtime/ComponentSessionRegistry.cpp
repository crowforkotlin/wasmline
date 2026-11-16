/**
 * Implements the native Component Model session registry.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */

#include "wasmline/internal/runtime/ComponentSessionRegistry.h"

#include <mutex>
#include <thread>

#include "wasmline/internal/logging/NativeLogger.h"
#include "wasmline/runtime/Component.h"
#include "wasmline/runtime/ComponentHostHandler.h"
#include "wasmline/runtime/ComponentSession.h"
#include "wasmline/runtime/Engine.h"
#include "wasmline/runtime/OutboundHandler.h"

namespace wasmline {
    ComponentSessionRegistry::ComponentSessionRegistry(std::shared_mutex& lifecycleMutex) noexcept : lifecycleMutex_(lifecycleMutex) {}

    std::shared_ptr<ComponentSession> ComponentSessionRegistry::findImplicit(const std::string& artifactKey) const {
        std::shared_lock<std::shared_mutex> lock(mutex_);
        const auto found = implicitSessions_.find(artifactKey);
        return found == implicitSessions_.end() ? nullptr : found->second;
    }

    std::shared_ptr<ComponentSession> ComponentSessionRegistry::findExplicit(const std::string& instanceKey) const {
        std::shared_lock<std::shared_mutex> lock(mutex_);
        const auto found = explicitSessions_.find(instanceKey);
        return found == explicitSessions_.end() ? nullptr : found->second.session;
    }

    std::shared_ptr<ComponentSession> ComponentSessionRegistry::create(const std::string& artifactKey, const std::string& instanceKey) {
        wasm_engine_t* engine = Engine::getInstance().getEngine();
        wasmtime_component_t* component = Component::getInstance().get(artifactKey);
        if (!engine || !component) return nullptr;
        return std::make_shared<ComponentSession>(engine, component, instanceKey);
    }

    void ComponentSessionRegistry::discardPendingImplicit(const std::string& artifactKey,
                                                         const std::shared_ptr<ComponentSession>& session) {
        std::unique_lock<std::shared_mutex> lock(mutex_);
        const auto pending = pendingImplicitSessions_.find(artifactKey);
        if (pending != pendingImplicitSessions_.end() && pending->second.session == session) {
            pendingImplicitSessions_.erase(pending);
        }
        creationSignal_.notify_all();
    }

    void ComponentSessionRegistry::discardPendingExplicit(const std::string& instanceKey,
                                                         const std::shared_ptr<ComponentSession>& session) {
        std::unique_lock<std::shared_mutex> lock(mutex_);
        const auto pending = pendingExplicitSessions_.find(instanceKey);
        if (pending != pendingExplicitSessions_.end() && pending->second.session == session) {
            pendingExplicitSessions_.erase(pending);
        }
    }

    std::shared_ptr<ComponentSession> ComponentSessionRegistry::getOrCreate(const std::string& artifactKey) {
        if (const auto cached = findImplicit(artifactKey)) return cached;

        std::shared_ptr<ComponentSession> session;
        {
            std::shared_lock<std::shared_mutex> lifecycleLock(lifecycleMutex_);
            std::unique_lock<std::shared_mutex> lock(mutex_);
            while (true) {
                if (const auto cached = implicitSessions_.find(artifactKey); cached != implicitSessions_.end()) {
                    return cached->second;
                }
                const auto pending = pendingImplicitSessions_.find(artifactKey);
                if (pending == pendingImplicitSessions_.end()) break;
                // Guest start callbacks run without registry locks, so a nested
                // request for the session that is still initializing reports a
                // failure instead of waiting on the caller itself.
                if (pending->second.owner == std::this_thread::get_id()) return nullptr;
                creationSignal_.wait(lock);
            }
            session = create(artifactKey, artifactKey);
            if (!session) return nullptr;
            pendingImplicitSessions_.emplace(artifactKey, PendingSession{session, std::this_thread::get_id()});
        }

        bool initialized = false;
        try {
            initialized = session->initialize();
        } catch (...) {
            discardPendingImplicit(artifactKey, session);
            throw;
        }
        if (!initialized) {
            discardPendingImplicit(artifactKey, session);
            return nullptr;
        }

        std::unique_lock<std::shared_mutex> lock(mutex_);
        const auto pending = pendingImplicitSessions_.find(artifactKey);
        if (pending == pendingImplicitSessions_.end() || pending->second.session != session) {
            session.reset();
            creationSignal_.notify_all();
            return nullptr;
        }
        pendingImplicitSessions_.erase(pending);
        const auto [cached, inserted] = implicitSessions_.emplace(artifactKey, session);
        creationSignal_.notify_all();
        return cached->second;
    }

    InvocationResult ComponentSessionRegistry::invoke(const std::string& artifactKey, std::string_view exportName,
                                                      const std::vector<ComponentValue>& arguments) {
        const auto session = getOrCreate(artifactKey);
        if (!session) {
            return InvocationResult::failure(WasmlineErrorCode::ENGINE_NOT_INITIALIZED, "Component session could not be created.");
        }
        return session->invoke(exportName, arguments);
    }

    void ComponentSessionRegistry::setOutboundHandler(const std::string& artifactKey, std::string codec,
                                                      std::unique_ptr<OutboundHandler> handler) {
        const auto session = getOrCreate(artifactKey);
        if (session) session->setOutboundHandler(std::move(handler), std::move(codec));
    }

    bool ComponentSessionRegistry::setHostHandler(const std::string& artifactKey, std::unique_ptr<ComponentHostHandler> handler) {
        if (!handler) return false;

        std::shared_ptr<ComponentSession> session;
        {
            std::shared_lock<std::shared_mutex> lifecycleLock(lifecycleMutex_);
            std::unique_lock<std::shared_mutex> lock(mutex_);
            while (true) {
                if (const auto cached = implicitSessions_.find(artifactKey); cached != implicitSessions_.end()) {
                    lock.unlock();
                    cached->second->setComponentHostHandler(std::move(handler));
                    return true;
                }
                const auto pending = pendingImplicitSessions_.find(artifactKey);
                if (pending == pendingImplicitSessions_.end()) break;
                if (pending->second.owner == std::this_thread::get_id()) {
                    LOGE("[Wasmtime] ComponentSessionRegistry --> Session is initializing on this thread: %s", artifactKey.c_str());
                    return false;
                }
                creationSignal_.wait(lock);
            }
            session = create(artifactKey, artifactKey);
            if (!session) {
                LOGE("[Wasmtime] ComponentSessionRegistry --> Cannot install host handler for %s", artifactKey.c_str());
                return false;
            }
            session->setComponentHostHandler(std::move(handler));
            pendingImplicitSessions_.emplace(artifactKey, PendingSession{session, std::this_thread::get_id()});
        }

        bool initialized = false;
        try {
            initialized = session->initialize();
        } catch (...) {
            discardPendingImplicit(artifactKey, session);
            throw;
        }
        if (!initialized) {
            discardPendingImplicit(artifactKey, session);
            LOGE("[Wasmtime] ComponentSessionRegistry --> Failed to initialize session for %s", artifactKey.c_str());
            return false;
        }

        std::unique_lock<std::shared_mutex> lock(mutex_);
        const auto pending = pendingImplicitSessions_.find(artifactKey);
        if (pending == pendingImplicitSessions_.end() || pending->second.session != session) {
            creationSignal_.notify_all();
            return false;
        }
        pendingImplicitSessions_.erase(pending);
        const bool inserted = implicitSessions_.emplace(artifactKey, session).second;
        creationSignal_.notify_all();
        return inserted;
    }

    bool ComponentSessionRegistry::instantiate(const std::string& artifactKey, const std::string& instanceKey,
                                               std::unique_ptr<ComponentHostHandler> handler) {
        if (artifactKey.empty() || instanceKey.empty() || !handler) return false;

        std::shared_ptr<ComponentSession> session;
        {
            std::shared_lock<std::shared_mutex> lifecycleLock(lifecycleMutex_);
            std::unique_lock<std::shared_mutex> lock(mutex_);
            if (explicitSessions_.count(instanceKey) || pendingExplicitSessions_.count(instanceKey)) return false;
            session = create(artifactKey, instanceKey);
            if (!session) return false;
            session->setComponentHostHandler(std::move(handler));
            pendingExplicitSessions_.emplace(instanceKey, PendingSession{session, std::this_thread::get_id()});
        }

        bool initialized = false;
        try {
            initialized = session->initialize();
        } catch (...) {
            discardPendingExplicit(instanceKey, session);
            throw;
        }
        if (!initialized) {
            discardPendingExplicit(instanceKey, session);
            return false;
        }

        std::unique_lock<std::shared_mutex> lock(mutex_);
        const auto pending = pendingExplicitSessions_.find(instanceKey);
        if (pending == pendingExplicitSessions_.end() || pending->second.session != session) return false;
        pendingExplicitSessions_.erase(pending);
        return explicitSessions_.emplace(instanceKey, ExplicitEntry{artifactKey, std::move(session)}).second;
    }

    InvocationResult ComponentSessionRegistry::invokeInstance(const std::string& instanceKey, std::string_view exportName,
                                                              const std::vector<ComponentValue>& arguments) {
        const auto session = findExplicit(instanceKey);
        if (!session) {
            return InvocationResult::failure(WasmlineErrorCode::SESSION_CLOSED,
                                             "Component instance is not initialized or has been closed.");
        }
        return session->invoke(exportName, arguments);
    }

    ResourceDropOutcome ComponentSessionRegistry::dropResource(const std::string& instanceKey,
                                                               const ComponentResourceReference& reference) {
        const auto session = findExplicit(instanceKey);
        if (!session) {
            return ResourceDropOutcome::rejected(WasmlineErrorCode::SESSION_CLOSED,
                                                 "Component instance is not initialized or has been closed.");
        }
        return session->dropResource(reference);
    }

    bool ComponentSessionRegistry::createHostResource(const std::string& instanceKey, std::string_view interfaceName,
                                                      std::string_view resourceName, uint32_t representation,
                                                      ComponentResourceReference* reference) {
        const auto session = findExplicit(instanceKey);
        return session && session->createHostResource(interfaceName, resourceName, representation, reference);
    }

    void ComponentSessionRegistry::release(const std::string& instanceKey) {
        std::shared_ptr<ComponentSession> session;
        {
            std::lock_guard<std::mutex> creationLock(creationMutex_);
            std::unique_lock<std::shared_mutex> lock(mutex_);
            const auto found = explicitSessions_.find(instanceKey);
            if (found != explicitSessions_.end()) {
                session = std::move(found->second.session);
                explicitSessions_.erase(found);
            }
            // A release during the instance's own start callback retires the
            // pending entry so the publishing thread observes the cancellation.
            pendingExplicitSessions_.erase(instanceKey);
        }
        session.reset();
    }

    std::vector<std::shared_ptr<ComponentSession>> ComponentSessionRegistry::detachArtifact(const std::string& artifactKey) {
        std::lock_guard<std::mutex> creationLock(creationMutex_);
        std::vector<std::shared_ptr<ComponentSession>> detached;
        {
            std::unique_lock<std::shared_mutex> lock(mutex_);
            if (const auto pending = pendingImplicitSessions_.find(artifactKey); pending != pendingImplicitSessions_.end()) {
                detached.push_back(std::move(pending->second.session));
                pendingImplicitSessions_.erase(pending);
                creationSignal_.notify_all();
            }
            detached.reserve(2 + explicitSessions_.size());
            const auto implicit = implicitSessions_.find(artifactKey);
            if (implicit != implicitSessions_.end()) {
                detached.push_back(std::move(implicit->second));
                implicitSessions_.erase(implicit);
            }
            for (auto session = explicitSessions_.begin(); session != explicitSessions_.end();) {
                if (session->second.artifactKey == artifactKey) {
                    detached.push_back(std::move(session->second.session));
                    session = explicitSessions_.erase(session);
                } else {
                    ++session;
                }
            }
        }
        return detached;
    }

    std::vector<std::shared_ptr<ComponentSession>> ComponentSessionRegistry::detachAll() {
        std::lock_guard<std::mutex> creationLock(creationMutex_);
        std::vector<std::shared_ptr<ComponentSession>> detached;
        {
            std::unique_lock<std::shared_mutex> lock(mutex_);
            detached.reserve(implicitSessions_.size() + explicitSessions_.size() +
                             pendingImplicitSessions_.size() + pendingExplicitSessions_.size());
            for (auto& entry : implicitSessions_)
                detached.push_back(std::move(entry.second));
            for (auto& entry : explicitSessions_)
                detached.push_back(std::move(entry.second.session));
            for (auto& entry : pendingImplicitSessions_)
                detached.push_back(std::move(entry.second.session));
            for (auto& entry : pendingExplicitSessions_)
                detached.push_back(std::move(entry.second.session));
            implicitSessions_.clear();
            explicitSessions_.clear();
            pendingImplicitSessions_.clear();
            pendingExplicitSessions_.clear();
            creationSignal_.notify_all();
        }
        return detached;
    }
} // namespace wasmline
