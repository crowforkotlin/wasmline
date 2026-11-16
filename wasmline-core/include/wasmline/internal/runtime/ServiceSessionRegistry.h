/**
 * Defines the internal native Wasmline Service session registry.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */

#pragma once

#include <condition_variable>
#include <memory>
#include <mutex>
#include <shared_mutex>
#include <string>
#include <thread>
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
        /**
         * Tracks one session whose reactor initialization is still running.
         *
         * Date: 2026-11-16
         * Author: crowforkotlin
         */
        struct SessionPending {
            /** Session being initialized. */
            std::shared_ptr<Session> session;
            /** Thread that runs the initialization. */
            std::thread::id owner;
        };

        void discardPending(const std::string& artifactKey, const std::shared_ptr<Session>& session);
        std::shared_ptr<Session> find(const std::string& artifactKey) const;

        std::shared_mutex& lifecycleMutex_;
        std::mutex creationMutex_;
        mutable std::shared_mutex mutex_;
        std::unordered_map<std::string, std::shared_ptr<Session>> sessions_;
        /** Sessions whose guest initialization is still running; never callable. */
        std::unordered_map<std::string, SessionPending> pendingSessions_;
        /** Signals waiters when a pending session is published or discarded. */
        std::condition_variable_any creationSignal_;
    };
} // namespace wasmline
