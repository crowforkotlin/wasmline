/**
 * WasmModule.h
 * Manages Wasmtime Modules.
 * Handles loading (compilation/deserialization), caching, and serialization.
 * Thread-safe using shared_mutex (Read-Write Lock).
 *
 * 2025-12-03
 * @author crowforkotlin
 */

#pragma once

#include <string>
#include <unordered_map>
#include <shared_mutex>
#include "wasmtime.h"

class WasmModule {
public:
    /**
     * Access singleton instance for Module management.
     */
    static WasmModule& getInstance();

    WasmModule(const WasmModule&) = delete;
    WasmModule& operator=(const WasmModule&) = delete;

    /**
     * Loads a module from file system.
     * Checks cache first. If not found, reads file and compiles/deserializes.
     *
     * @param key Unique identifier for the module
     * @param filePath Absolute path to the file
     * @param isJit True if source (.wasm), False if precompiled (.cwasm)
     * @return Raw pointer to wasmtime_module_t, or nullptr if failed.
     */
    wasmtime_module_t* load(const std::string& key, const std::string& filePath, bool isJit);

    /**
     * Retrieves an existing cached module.
     * @return module pointer or nullptr.
     */
    wasmtime_module_t* get(const std::string& key);

    /**
     * Serializes a loaded module to a cache file (AOT compilation).
     * @param key Module key
     * @param outPath Output file path
     * @return true if success
     */
    bool serialize(const std::string& key, const std::string& outPath);

    /**
     * Releases a specific module from cache.
     */
    void release(const std::string& key);

    /**
     * Clears all cached modules.
     */
    void clear();

private:
    WasmModule() = default;
    ~WasmModule();

    // Cache storage: Key -> Module Pointer
    std::unordered_map<std::string, wasmtime_module_t*> moduleCache;

    // Mutex for thread safety (Allows multiple readers, single writer)
    mutable std::shared_mutex cacheMutex;
};