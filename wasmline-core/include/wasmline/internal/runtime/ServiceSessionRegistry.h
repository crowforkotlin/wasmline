/**
 * Defines the internal native Wasmline Service session registry.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */

#pragma once

#include <memory>
#include <mutex>
#include <shared_mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace wasmline {
    class OutboundHandler;
    class Session;

    /**
     * Owns cached Core Wasmline Service sessions.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     */
    class ServiceSessionRegistry final {
    public:
        /** Creates a registry coordinated by the runtime lifecycle mutex. */
        explicit ServiceSessionRegistry(std::shared_mutex& lifecycleMutex) noexcept;

        /** Returns a retained session or creates it from a loaded artifact. */
        std::shared_ptr<Session> getOrCreate(const std::string& artifactKey);

        /** Installs an outbound handler on the artifact session. */
        void setOutboundHandler(const std::string& artifactKey, std::unique_ptr<OutboundHandler> handler);

        /** Detaches the session associated with an artifact. */
        std::vector<std::shared_ptr<Session>> detachArtifact(const std::string& artifactKey);

        /** Detaches all sessions. */
        std::vector<std::shared_ptr<Session>> detachAll();

    private:
        std::shared_ptr<Session> find(const std::string& artifactKey) const;

        std::shared_mutex& lifecycleMutex_;
        std::mutex creationMutex_;
        mutable std::shared_mutex mutex_;
        std::unordered_map<std::string, std::shared_ptr<Session>> sessions_;
    };
} // namespace wasmline
