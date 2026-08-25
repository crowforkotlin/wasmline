/**
 * Defines the internal native Core Wasm raw session registry.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */

#pragma once

#include <cstdint>
#include <memory>
#include <mutex>
#include <shared_mutex>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "wasmline/invocation/RawWasmTypes.h"

namespace wasmline {
    class RawModuleSession;

    /**
     * Owns implicit and explicitly instantiated Core Wasm raw sessions.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     */
    class RawSessionRegistry final {
    public:
        /** Creates a registry coordinated by the runtime lifecycle mutex. */
        explicit RawSessionRegistry(std::shared_mutex& lifecycleMutex) noexcept;

        /** Invokes an export on the artifact's implicit raw session. */
        InvocationResult invoke(const std::string& artifactKey, std::string_view exportName, const std::vector<RawValue>& arguments);

        /** Instantiates one explicit raw session. */
        InvocationResult instantiate(const std::string& artifactKey, const std::string& sessionKey,
                                     const std::vector<RawImportDefinition>& imports, RawImportCallback callback,
                                     RawImportBufferFree bufferFree, void* callbackUser, RawImportUserFinalizer userFinalizer,
                                     std::string memoryExportName);

        /** Invokes an export on an explicit raw session. */
        InvocationResult invokeInstance(const std::string& sessionKey, std::string_view exportName, const std::vector<RawValue>& arguments);

        /** Reads bytes from an explicit raw session. */
        InvocationResult readMemory(const std::string& sessionKey, uint64_t offset, uint64_t length, std::vector<uint8_t>* output);

        /** Writes bytes to an explicit raw session. */
        InvocationResult writeMemory(const std::string& sessionKey, uint64_t offset, const uint8_t* bytes, uint64_t length);

        /** Returns an explicit raw session memory size. */
        InvocationResult memorySize(const std::string& sessionKey, bool pages);

        /** Grows an explicit raw session memory. */
        InvocationResult growMemory(const std::string& sessionKey, uint64_t deltaPages);

        /** Releases one explicit raw session. */
        void release(const std::string& sessionKey);

        /** Detaches every raw session associated with an artifact. */
        std::vector<std::shared_ptr<RawModuleSession>> detachArtifact(const std::string& artifactKey);

        /** Detaches all raw sessions. */
        std::vector<std::shared_ptr<RawModuleSession>> detachAll();

    private:
        /**
         * Associates an explicit raw session with its source artifact.
         *
         * Date: 2026-08-25
         * Author: crowforkotlin
         */
        struct ExplicitEntry {
            /** Source artifact key. */
            std::string artifactKey;
            /** Retained session. */
            std::shared_ptr<RawModuleSession> session;
        };

        std::shared_ptr<RawModuleSession> findImplicit(const std::string& artifactKey) const;
        std::shared_ptr<RawModuleSession> findExplicit(const std::string& sessionKey) const;
        std::shared_ptr<RawModuleSession> getOrCreate(const std::string& artifactKey);

        std::shared_mutex& lifecycleMutex_;
        std::mutex creationMutex_;
        mutable std::shared_mutex mutex_;
        std::unordered_map<std::string, std::shared_ptr<RawModuleSession>> implicitSessions_;
        std::unordered_map<std::string, ExplicitEntry> explicitSessions_;
    };
} // namespace wasmline
