/**
 * Implements the native Wasmline Service session registry.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */

#include "wasmline/internal/runtime/ServiceSessionRegistry.h"

#include <mutex>
#include <thread>

#include "wasmline/internal/logging/NativeLogger.h"
#include "wasmline/runtime/Engine.h"
#include "wasmline/runtime/Module.h"
#include "wasmline/runtime/OutboundHandler.h"
#include "wasmline/runtime/Session.h"

namespace wasmline {
    ServiceSessionRegistry::ServiceSessionRegistry(std::shared_mutex& lifecycleMutex) noexcept : lifecycleMutex_(lifecycleMutex) {}

    std::shared_ptr<Session> ServiceSessionRegistry::find(const std::string& artifactKey) const {
        std::shared_lock<std::shared_mutex> lock(mutex_);
        const auto found = sessions_.find(artifactKey);
        return found == sessions_.end() ? nullptr : found->second;
    }

    std::shared_ptr<Session> ServiceSessionRegistry::getOrCreate(const std::string& artifactKey) {
        if (const auto cached = find(artifactKey)) return cached;

        std::shared_ptr<Session> session;
        {
            std::shared_lock<std::shared_mutex> lifecycleLock(lifecycleMutex_);
            std::unique_lock<std::shared_mutex> lock(mutex_);
            while (true) {
                if (const auto cached = sessions_.find(artifactKey); cached != sessions_.end()) {
                    return cached->second;
                }
                const auto pending = pendingSessions_.find(artifactKey);
                if (pending == pendingSessions_.end()) break;
                // The reactor initialization of this session runs on the calling
                // thread inside initialize(); a nested request cannot complete.
                if (pending->second.owner == std::this_thread::get_id()) return nullptr;
                creationSignal_.wait(lock);
            }

            wasm_engine_t* engine = Engine::getInstance().getEngine();
            wasmtime_module_t* module = Module::getInstance().get(artifactKey);
            if (!engine || !module) {
                LOGE("[Wasmtime] ServiceSessionRegistry --> Engine or artifact is null for %s", artifactKey.c_str());
                return nullptr;
            }
            session = std::make_shared<Session>(engine, module, artifactKey);
            pendingSessions_.emplace(artifactKey, SessionPending{session, std::this_thread::get_id()});
        }

        bool initialized = false;
        try {
            initialized = session->initialize();
        } catch (...) {
            discardPending(artifactKey, session);
            throw;
        }
        if (!initialized) {
            discardPending(artifactKey, session);
            LOGE("[Wasmtime] ServiceSessionRegistry --> Failed to initialize session for %s", artifactKey.c_str());
            return nullptr;
        }

        std::unique_lock<std::shared_mutex> lock(mutex_);
        const auto pending = pendingSessions_.find(artifactKey);
        if (pending == pendingSessions_.end() || pending->second.session != session) {
            session.reset();
            creationSignal_.notify_all();
            return nullptr;
        }
        pendingSessions_.erase(pending);
        const auto [cached, inserted] = sessions_.emplace(artifactKey, session);
        creationSignal_.notify_all();
        return cached->second;
    }

    void ServiceSessionRegistry::discardPending(const std::string& artifactKey, const std::shared_ptr<Session>& session) {
        std::unique_lock<std::shared_mutex> lock(mutex_);
        const auto pending = pendingSessions_.find(artifactKey);
        if (pending != pendingSessions_.end() && pending->second.session == session) {
            pendingSessions_.erase(pending);
        }
        creationSignal_.notify_all();
    }

    void ServiceSessionRegistry::setOutboundHandler(const std::string& artifactKey, std::unique_ptr<OutboundHandler> handler) {
        const auto session = getOrCreate(artifactKey);
        if (session) session->setOutboundHandler(std::move(handler));
    }

    std::vector<std::shared_ptr<Session>> ServiceSessionRegistry::detachArtifact(const std::string& artifactKey) {
        std::lock_guard<std::mutex> creationLock(creationMutex_);
        std::vector<std::shared_ptr<Session>> detached;
        {
            std::unique_lock<std::shared_mutex> lock(mutex_);
            detached.reserve(2);
            if (const auto pending = pendingSessions_.find(artifactKey); pending != pendingSessions_.end()) {
                detached.push_back(std::move(pending->second.session));
                pendingSessions_.erase(pending);
                creationSignal_.notify_all();
            }
            const auto found = sessions_.find(artifactKey);
            if (found != sessions_.end()) {
                detached.push_back(std::move(found->second));
                sessions_.erase(found);
            }
        }
        return detached;
    }

    std::vector<std::shared_ptr<Session>> ServiceSessionRegistry::detachAll() {
        std::lock_guard<std::mutex> creationLock(creationMutex_);
        std::vector<std::shared_ptr<Session>> detached;
        {
            std::unique_lock<std::shared_mutex> lock(mutex_);
            detached.reserve(sessions_.size() + pendingSessions_.size());
            for (auto& entry : sessions_)
                detached.push_back(std::move(entry.second));
            for (auto& entry : pendingSessions_)
                detached.push_back(std::move(entry.second.session));
            sessions_.clear();
            pendingSessions_.clear();
            creationSignal_.notify_all();
        }
        return detached;
    }
} // namespace wasmline
