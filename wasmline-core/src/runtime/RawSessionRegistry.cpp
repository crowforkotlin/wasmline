/**
 * Implements the native Core Wasm raw session registry.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */

#include "wasmline/internal/runtime/RawSessionRegistry.h"

#include <mutex>

#include "wasmline/internal/logging/NativeLogger.h"
#include "wasmline/runtime/Engine.h"
#include "wasmline/runtime/Module.h"
#include "wasmline/runtime/RawModuleSession.h"

namespace wasmline {
    namespace {
        InvocationResult closedRawSession() {
            return InvocationResult::failure(WasmlineErrorCode::SESSION_CLOSED,
                                             "Raw module session is not initialized or has been closed.");
        }

        void ignoreImportUser(void*) {}

        using ImportUserOwner = std::unique_ptr<void, RawImportUserFinalizer>;

        ImportUserOwner ownImportUser(void* user, RawImportUserFinalizer finalizer) {
            return ImportUserOwner(user, finalizer ? finalizer : &ignoreImportUser);
        }
    } // namespace

    RawSessionRegistry::RawSessionRegistry(std::shared_mutex& lifecycleMutex) noexcept : lifecycleMutex_(lifecycleMutex) {}

    std::shared_ptr<RawModuleSession> RawSessionRegistry::findImplicit(const std::string& artifactKey) const {
        std::shared_lock<std::shared_mutex> lock(mutex_);
        const auto found = implicitSessions_.find(artifactKey);
        return found == implicitSessions_.end() ? nullptr : found->second;
    }

    std::shared_ptr<RawModuleSession> RawSessionRegistry::findExplicit(const std::string& sessionKey) const {
        std::shared_lock<std::shared_mutex> lock(mutex_);
        const auto found = explicitSessions_.find(sessionKey);
        return found == explicitSessions_.end() ? nullptr : found->second.session;
    }

    std::shared_ptr<RawModuleSession> RawSessionRegistry::getOrCreate(const std::string& artifactKey) {
        if (const auto cached = findImplicit(artifactKey)) return cached;

        std::shared_lock<std::shared_mutex> lifecycleLock(lifecycleMutex_);
        std::lock_guard<std::mutex> creationLock(creationMutex_);
        if (const auto cached = findImplicit(artifactKey)) return cached;

        wasm_engine_t* engine = Engine::getInstance().getEngine();
        wasmtime_module_t* module = Module::getInstance().get(artifactKey);
        if (!engine || !module) {
            LOGE("[Wasmtime] RawSessionRegistry --> Engine or artifact is null for %s", artifactKey.c_str());
            return nullptr;
        }

        auto session = std::make_shared<RawModuleSession>(engine, module, artifactKey);
        if (!session->initialize()) {
            LOGE("[Wasmtime] RawSessionRegistry --> Failed to initialize session for %s", artifactKey.c_str());
            return nullptr;
        }

        std::unique_lock<std::shared_mutex> lock(mutex_);
        const auto [cached, inserted] = implicitSessions_.emplace(artifactKey, session);
        return inserted ? std::move(session) : cached->second;
    }

    InvocationResult RawSessionRegistry::invoke(const std::string& artifactKey, std::string_view exportName,
                                                const std::vector<RawValue>& arguments) {
        const auto session = getOrCreate(artifactKey);
        if (!session) {
            return InvocationResult::failure(WasmlineErrorCode::ENGINE_NOT_INITIALIZED, "Raw module session could not be created.");
        }
        return session->invoke(exportName, arguments);
    }

    InvocationResult RawSessionRegistry::instantiate(const std::string& artifactKey, const std::string& sessionKey,
                                                     const std::vector<RawImportDefinition>& imports, RawImportCallback callback,
                                                     RawImportBufferFree bufferFree, void* callbackUser,
                                                     RawImportUserFinalizer userFinalizer, std::string memoryExportName) {
        auto callbackUserOwner = ownImportUser(callbackUser, userFinalizer);
        if (artifactKey.empty() || sessionKey.empty()) {
            return InvocationResult::failure(WasmlineErrorCode::INSTANTIATION_FAILED, "Raw artifact and session keys must not be empty.");
        }

        std::shared_ptr<RawModuleSession> session;
        std::shared_lock<std::shared_mutex> lifecycleLock(lifecycleMutex_);
        std::unique_lock<std::mutex> creationLock(creationMutex_);
        if (findExplicit(sessionKey)) {
            return InvocationResult::failure(WasmlineErrorCode::CONCURRENT_ACCESS, "Raw session key is already active.");
        }

        wasm_engine_t* engine = Engine::getInstance().getEngine();
        wasmtime_module_t* module = Module::getInstance().get(artifactKey);
        if (!engine || !module) {
            return InvocationResult::failure(WasmlineErrorCode::ENGINE_NOT_INITIALIZED, "Raw module artifact is not loaded.");
        }

        session = std::make_shared<RawModuleSession>(engine, module, sessionKey);
        session->adoptImportUser(callbackUserOwner.release(), userFinalizer);
        if (!session->initialize(imports, callback, bufferFree, false, std::move(memoryExportName))) {
            InvocationResult failure = session->takeLastImportFailure();
            if (failure.isSuccess()) {
                failure =
                    InvocationResult::failure(WasmlineErrorCode::INSTANTIATION_FAILED, "Raw module session could not be instantiated.");
            }
            return failure;
        }

        std::unique_lock<std::shared_mutex> lock(mutex_);
        if (!explicitSessions_.emplace(sessionKey, ExplicitEntry{artifactKey, session}).second) {
            lock.unlock();
            return InvocationResult::failure(WasmlineErrorCode::CONCURRENT_ACCESS, "Raw session key became active during instantiation.");
        }
        return InvocationResult::success();
    }

    InvocationResult RawSessionRegistry::invokeInstance(const std::string& sessionKey, std::string_view exportName,
                                                        const std::vector<RawValue>& arguments) {
        const auto session = findExplicit(sessionKey);
        return session ? session->invoke(exportName, arguments) : closedRawSession();
    }

    InvocationResult RawSessionRegistry::readMemory(const std::string& sessionKey, uint64_t offset, uint8_t* destination, uint64_t length) {
        const auto session = findExplicit(sessionKey);
        return session ? session->readMemory(offset, destination, length) : closedRawSession();
    }

    InvocationResult RawSessionRegistry::writeMemory(const std::string& sessionKey, uint64_t offset, const uint8_t* bytes,
                                                     uint64_t length) {
        const auto session = findExplicit(sessionKey);
        return session ? session->writeMemory(offset, bytes, length) : closedRawSession();
    }

    InvocationResult RawSessionRegistry::memorySize(const std::string& sessionKey, bool pages) {
        const auto session = findExplicit(sessionKey);
        return session ? session->memorySize(pages) : closedRawSession();
    }

    InvocationResult RawSessionRegistry::growMemory(const std::string& sessionKey, uint64_t deltaPages) {
        const auto session = findExplicit(sessionKey);
        return session ? session->growMemory(deltaPages) : closedRawSession();
    }

    void RawSessionRegistry::release(const std::string& sessionKey) {
        std::shared_ptr<RawModuleSession> session;
        {
            std::lock_guard<std::mutex> creationLock(creationMutex_);
            {
                std::unique_lock<std::shared_mutex> lock(mutex_);
                const auto found = explicitSessions_.find(sessionKey);
                if (found != explicitSessions_.end()) {
                    session = std::move(found->second.session);
                    explicitSessions_.erase(found);
                }
            }
        }
        session.reset();
    }

    std::vector<std::shared_ptr<RawModuleSession>> RawSessionRegistry::detachArtifact(const std::string& artifactKey) {
        std::lock_guard<std::mutex> creationLock(creationMutex_);
        std::vector<std::shared_ptr<RawModuleSession>> detached;
        {
            std::unique_lock<std::shared_mutex> lock(mutex_);
            detached.reserve(1 + explicitSessions_.size());
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

    std::vector<std::shared_ptr<RawModuleSession>> RawSessionRegistry::detachAll() {
        std::lock_guard<std::mutex> creationLock(creationMutex_);
        std::vector<std::shared_ptr<RawModuleSession>> detached;
        {
            std::unique_lock<std::shared_mutex> lock(mutex_);
            detached.reserve(implicitSessions_.size() + explicitSessions_.size());
            for (auto& entry : implicitSessions_)
                detached.push_back(std::move(entry.second));
            for (auto& entry : explicitSessions_)
                detached.push_back(std::move(entry.second.session));
            implicitSessions_.clear();
            explicitSessions_.clear();
        }
        return detached;
    }
} // namespace wasmline
