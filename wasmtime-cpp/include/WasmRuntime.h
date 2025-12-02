/**
 * Global Manager for Wasmtime Engine, Modules, and Sessions.
 * Implements the Singleton pattern to ensure a single Engine instance.
 * Handles caching, thread-safety, and resource lifecycle management.
 *
 * 2025-12-02
 * @author crowforkotlin / crowforkotlin@gmail.com
 */

#pragma once

#include <string>
#include <unordered_map>
#include <shared_mutex>
#include <mutex>
#include "WasmSession.h"
#include "wasmtime.h"

class WasmRuntime {
public:
    /**
     * Access the singleton instance.
     * Thread-safe initialization guaranteed by C++11 static local rules.
     */
    static WasmRuntime& getInstance();

    // Delete copy constructor and assignment operator to enforce Singleton
    WasmRuntime(const WasmRuntime&) = delete;
    WasmRuntime& operator=(const WasmRuntime&) = delete;

    /**
     * Initializes the global Wasmtime Engine.
     * Should be called once at application startup.
     */
    void initEngine();
    
    /**
     * Releases the engine and ALL cached resources (Sessions, Modules).
     * Call this when the Wasm functionality is no longer needed (e.g., App termination).
     */
    void releaseEngine();

    /**
     * Loads a Wasm module from a file path.
     * Caches the compiled module using the provided key.
     *
     * @param key Unique identifier for the module
     * @param filePath Absolute path to the .wasm or .cwasm file
     * @param isJit True for source code (.wasm), False for precompiled binary (.cwasm)
     * @return Pointer to the loaded module, or nullptr if failed
     */
    wasmtime_module_t* loadModule(const std::string& key, const std::string& filePath, bool isJit);
    
    /**
     * Retrieves an existing cached module.
     * Thread-safe (Reader Lock).
     */
    wasmtime_module_t* getModule(const std::string& key);

    /**
     * Releases a specific module and its associated sessions.
     * Useful for freeing memory when a specific feature is closed.
     */
    void releaseModule(const std::string& key);

    /**
     * Gets an existing Session or creates a new one if it doesn't exist.
     * A Session maintains the runtime state (Memory, Global Variables) for a module.
     *
     * @param key Unique identifier (must match the module key)
     * @return Pointer to the active WasmSession
     */
    WasmSession* getSession(const std::string& key);
    
    /**
     * Releases a specific session instance.
     * Does NOT release the underlying Module (so it can be re-instantiated quickly).
     */
    void releaseSession(const std::string& key);

private:
    WasmRuntime() = default;
    ~WasmRuntime();

    /**
     * Creates and configures the Wasm Config object.
     * Applies critical Android-specific optimizations (Stack size, Signal handlers, etc.).
     */
    wasm_config_t* createConfig();

    wasm_engine_t* engine = nullptr;
    
    // Caches
    std::unordered_map<std::string, wasmtime_module_t*> moduleCache;
    std::unordered_map<std::string, WasmSession*> sessionCache;

    // Concurrency: Use shared_mutex for Read-Write Lock pattern
    // Multiple readers can access caches simultaneously; writers block everyone.
    mutable std::shared_mutex resourceMutex;
};