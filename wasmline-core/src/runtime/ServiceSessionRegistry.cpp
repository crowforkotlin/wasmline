/**
 * Implements the native Wasmline Service session registry.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */

#include "wasmline/internal/runtime/ServiceSessionRegistry.h"

#include <mutex>

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

        std::shared_lock<std::shared_mutex> lifecycleLock(lifecycleMutex_);
        std::lock_guard<std::mutex> creationLock(creationMutex_);
        if (const auto cached = find(artifactKey)) return cached;

        wasm_engine_t* engine = Engine::getInstance().getEngine();
        wasmtime_module_t* module = Module::getInstance().get(artifactKey);
        if (!engine || !module) {
            LOGE("[Wasmtime] ServiceSessionRegistry --> Engine or artifact is null for %s", artifactKey.c_str());
            return nullptr;
        }

        auto session = std::make_shared<Session>(engine, module, artifactKey);
        if (!session->initialize()) {
            LOGE("[Wasmtime] ServiceSessionRegistry --> Failed to initialize session for %s", artifactKey.c_str());
            return nullptr;
        }

        std::unique_lock<std::shared_mutex> lock(mutex_);
        const auto [cached, inserted] = sessions_.emplace(artifactKey, session);
        return inserted ? std::move(session) : cached->second;
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
            detached.reserve(1);
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
            detached.reserve(sessions_.size());
            for (auto& entry : sessions_)
                detached.push_back(std::move(entry.second));
            sessions_.clear();
        }
        return detached;
    }
} // namespace wasmline
