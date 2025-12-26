/**
 * Module.h
 * Manages Wasmtime Modules.
 * Handles loading (compilation/deserialization), caching, and serialization.
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
        static Module &getInstance();

        Module(const Module &) = delete;

        Module &operator=(const Module &) = delete;

        /**
         * Loads a module from file system (Thread-Safe & Optimized).
         *
         * Features:
         * 1. No Redundant IO/Compilation
         * 2. Lock Merging: Minimizes lock contention overhead.
         * 3. Safety: Uses RAII to prevent deadlocks.
         *
         * @param key Unique identifier for the module
         * @param filePath Absolute path to the file
         * @param isJit True if source (.wasm), False if precompiled (.cwasm)
         * @return Raw pointer to wasmtime_module_t, or nullptr if failed.
         */
        wasmtime_module_t *load(const std::string &key, const std::string &filePath, bool isJit);

        /**
         * Loads a module WITHOUT any thread safety mechanisms.
         *
         * WARNING: Use this ONLY during single-threaded initialization or when
         * you guarantee no other thread is accessing Module.
         * This provides the absolute fastest path by removing all locking overhead.
         *
         * @param key Unique identifier for the module
         * @param filePath Absolute path to the file
         * @param isJit True if source (.wasm), False if precompiled (.cwasm)
         * @return Raw pointer to wasmtime_module_t, or nullptr if failed.
         */
        wasmtime_module_t *loadUnsafe(const std::string &key, const std::string &filePath, bool isJit);

        /**
         * Retrieves an existing cached module.
         * @return module pointer or nullptr.
         */
        wasmtime_module_t *get(const std::string &key);

        /**
         * Serializes a loaded module to a cache file (AOT compilation).
         * @return true if success
         */
        bool serialize(const std::string &key, const std::string &outPath);

        /**
         * Serializes a loaded module to a cache file (AOT compilation).
         * @return true if success
         */
        bool serializeUnsafe(const std::string &key, const std::string &outPath);

        /**
         * Releases a specific module from cache.
         */
        void release(const std::string &key);

        /**
         * Clears all cached modules.
         */
        void clear();

    private:
        Module() = default;

        ~Module();


        // Core logic: Read File -> Get Engine -> Compile/Deserialize
        wasmtime_module_t *compileInternal(const std::string &key, const std::string &filePath, bool isJit);

        // Core logic: Wasm Serialize -> Write File
        bool serializeInternal(const std::string &key, wasmtime_module_t *module, const std::string &outPath);

        // Cache storage: Key -> Module Pointer
        std::unordered_map<std::string, wasmtime_module_t *> moduleCache;

        // Tracks keys currently being loaded by any thread
        std::unordered_set<std::string> loadingSet;

        // Main Mutex (Protects both moduleCache and loadingSet)
        // NOTE: std::mutex is preferred over shared_mutex here because
        // critical sections are extremely short (nanoseconds), making rw-lock overhead unnecessary.
        mutable std::mutex cacheMutex;

        // Condition Variable for thread coordination
        std::condition_variable cv;
    };
}