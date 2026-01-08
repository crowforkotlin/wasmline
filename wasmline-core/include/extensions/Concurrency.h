/**
 * WasmConcurrency.h
 * Generic concurrency utilities for thread synchronization.
 * Implements RAII pattern to ensure state cleanup and thread notification
 * under all execution paths (success, failure, or exception).
 *
 * 2025-12-04
 * @author crowforkotlin
 */

#pragma once

#include <mutex>
#include <unordered_set>
#include <condition_variable>
#include <string>

namespace wasmline {
    /**
     * A generic RAII guard for managing thread synchronization states.
     * Automatically cleans up a specific key from a tracking set and notifies waiting threads
     * when the object goes out of scope, unless manually committed.
     */
    class WasmScopeGuard {
    public:
        /**
         * @param m The mutex protecting the shared state.
         * @param set The set tracking active keys (e.g., loadingSet).
         * @param cv The condition variable to notify waiters.
         * @param k The specific key to manage.
         */
        WasmScopeGuard(std::mutex &m, std::unordered_set<std::string> &set, std::condition_variable &c, const std::string &k)
                : mutex(m), trackingSet(set), cv(c), key(k), isCommitted(false) {}

        /**
         * Marks the operation as successfully completed by the caller.
         * The destructor will NO LONGER perform auto-cleanup.
         * Call this when you want to merge the cleanup logic into an existing locked section for performance.
         */
        void commit() {
            isCommitted = true;
        }

        /**
         * Destructor.
         * If not committed, it acquires the lock, removes the key, and notifies all threads.
         * This guarantees no deadlocks or infinite waits in case of errors/exceptions.
         */
        ~WasmScopeGuard() {
            if (!isCommitted) {
                std::lock_guard<std::mutex> lock(mutex);
                trackingSet.erase(key);
                cv.notify_all();
            }
        }

    private:
        std::mutex &mutex;
        std::unordered_set<std::string> &trackingSet;
        std::condition_variable &cv;
        std::string key;
        bool isCommitted;
    };
}