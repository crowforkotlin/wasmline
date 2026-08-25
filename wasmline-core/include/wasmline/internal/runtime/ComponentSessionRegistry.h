/**
 * Defines the internal native Component Model session registry.
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

#include "wasmline/invocation/InvocationResult.h"

namespace wasmline {
    class ComponentHostHandler;
    class ComponentSession;
    class OutboundHandler;

    /**
     * Owns implicit and explicitly instantiated Component Model sessions.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     */
    class ComponentSessionRegistry final {
    public:
        /** Creates a registry coordinated by the runtime lifecycle mutex. */
        explicit ComponentSessionRegistry(std::shared_mutex& lifecycleMutex) noexcept;

        /** Invokes an export on the artifact's implicit Component session. */
        InvocationResult invoke(const std::string& artifactKey, std::string_view exportName, const std::vector<ComponentValue>& arguments);

        /** Installs a Wasmline Service host handler on an implicit session. */
        void setOutboundHandler(const std::string& artifactKey, std::string codec, std::unique_ptr<OutboundHandler> handler);

        /** Installs a typed Component host handler on an implicit session. */
        bool setHostHandler(const std::string& artifactKey, std::unique_ptr<ComponentHostHandler> handler);

        /** Instantiates one explicit Component session. */
        bool instantiate(const std::string& artifactKey, const std::string& instanceKey, std::unique_ptr<ComponentHostHandler> handler);

        /** Invokes an export on an explicit Component session. */
        InvocationResult invokeInstance(const std::string& instanceKey, std::string_view exportName,
                                        const std::vector<ComponentValue>& arguments);

        /** Drops one owned Component resource. */
        bool dropResource(const std::string& instanceKey, const ComponentResourceReference& reference);

        /** Creates one owned imported Host resource. */
        bool createHostResource(const std::string& instanceKey, std::string_view interfaceName, std::string_view resourceName,
                                uint32_t representation, ComponentResourceReference* reference);

        /** Releases one explicit Component session. */
        void release(const std::string& instanceKey);

        /** Detaches every Component session associated with an artifact. */
        std::vector<std::shared_ptr<ComponentSession>> detachArtifact(const std::string& artifactKey);

        /** Detaches all Component sessions. */
        std::vector<std::shared_ptr<ComponentSession>> detachAll();

    private:
        /**
         * Associates an explicit Component session with its source artifact.
         *
         * Date: 2026-08-25
         * Author: crowforkotlin
         */
        struct ExplicitEntry {
            /** Source artifact key. */
            std::string artifactKey;
            /** Retained session. */
            std::shared_ptr<ComponentSession> session;
        };

        std::shared_ptr<ComponentSession> findImplicit(const std::string& artifactKey) const;
        std::shared_ptr<ComponentSession> findExplicit(const std::string& instanceKey) const;
        std::shared_ptr<ComponentSession> getOrCreate(const std::string& artifactKey);
        std::shared_ptr<ComponentSession> create(const std::string& artifactKey, const std::string& instanceKey);

        std::shared_mutex& lifecycleMutex_;
        std::mutex creationMutex_;
        mutable std::shared_mutex mutex_;
        std::unordered_map<std::string, std::shared_ptr<ComponentSession>> implicitSessions_;
        std::unordered_map<std::string, ExplicitEntry> explicitSessions_;
    };
} // namespace wasmline
