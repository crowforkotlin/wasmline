/**
 * Module.h
 * Manages Wasmtime Modules.
 * Handles loading (raw wasm compilation / precompiled artifact deserialization),
 * caching, and serialization.
 *
 * 2025-12-03
 * @author crowforkotlin
 */

#pragma once

#include <string>
#include <unordered_map>
#include <unordered_set>
#include <mutex> // Use std::mutex for best compatibility with condition_variable
#include <condition_variable>
#include "wasmtime.h"

namespace wasmline {
    class Module {
    public:
        /**
         * Access singleton instance for Module management.
         */
        static Module& getInstance();

        Module(const Module&) = delete;

        Module& operator=(const Module&) = delete;

        /**
         * Loads a module artifact from file system (Thread-Safe & Optimized).
         * Supports raw `.wasm` and precompiled `.cwasm` / `.pwasm`.
         *
         * Features:
         * 1. No redundant IO/deserialization
         * 2. Lock Merging: Minimizes lock contention overhead.
         * 3. Safety: Uses RAII to prevent deadlocks.
         *
         * @param key Unique identifier for the module
         * @param filePath Absolute path to the module artifact file
         * @return Raw pointer to wasmtime_module_t, or nullptr if failed.
         */
        wasmtime_module_t* load(const std::string& key, const std::string& filePath);

        /**
         * Loads a module WITHOUT any thread safety mechanisms.
         * Supports raw `.wasm` and precompiled `.cwasm` / `.pwasm`.
         *
         * WARNING: Use this ONLY during single-threaded initialization or when
         * you guarantee no other thread is accessing Module.
         * This provides the absolute fastest path by removing all locking overhead.
         *
         * @param key Unique identifier for the module
         * @param filePath Absolute path to the module artifact file
         * @return Raw pointer to wasmtime_module_t, or nullptr if failed.
         */
        wasmtime_module_t* loadUnsafe(const std::string& key, const std::string& filePath);

        /**
         * Retrieves an existing cached module.
         * @return module pointer or nullptr.
         */
        wasmtime_module_t* get(const std::string& key);

        /**
         * Releases a specific module from cache.
         */
        void release(const std::string& key);

        /**
         * Clears all cached modules.
         */
        void clear();

    private:
        Module() = default;

        ~Module();

        // Core logic: Read file -> get engine -> compile raw wasm or deserialize precompiled artifact
        wasmtime_module_t* compileInternal(const std::string& key, const std::string& filePath);

        // Cache storage: Key -> Module Pointer
        std::unordered_map<std::string, wasmtime_module_t*> moduleCache;

        // Tracks keys currently being loaded by any thread
        std::unordered_set<std::string> loadingSet;

        // Main Mutex (Protects both moduleCache and loadingSet)
        // NOTE: std::mutex is preferred over shared_mutex here because
        // critical sections are extremely short (nanoseconds), making rw-lock overhead unnecessary.
        mutable std::mutex cacheMutex;

        // Condition Variable for thread coordination
        std::condition_variable cv;
    };
} // namespace wasmline