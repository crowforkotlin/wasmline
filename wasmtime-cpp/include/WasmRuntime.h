/**
 * Global manager for Wasmtime engine and module caching.
 * Singleton pattern.
 *
 * Date: 2025-12-02
 * Author: crowforkotlin
 */

#pragma once
#include <string>
#include <unordered_map>
#include <shared_mutex>
#include "WasmSession.h"
#include "wasmtime.h"

class WasmRuntime {
public:
    static WasmRuntime& getInstance();

    // Initializes the global engine (call once)
    void initEngine();
    
    // Releases engine and all resources
    void releaseEngine();

    // Loads a module from file or cache
    // isJit: true for source (.wasm), false for precompiled (.cwasm)
    wasmtime_module_t* loadModule(const std::string& key, const std::string& filePath, bool isJit);
    
    // Gets an existing module
    wasmtime_module_t* getModule(const std::string& key);

    // Releases a module and its associated sessions
    void releaseModule(const std::string& key);

    // Gets or creates a thread-safe session for a module
    WasmSession* getSession(const std::string& key);
    
    // Releases a specific session
    void releaseSession(const std::string& key);

private:
    WasmRuntime() = default;
    ~WasmRuntime();

    wasm_engine_t* engine = nullptr;
    
    std::unordered_map<std::string, wasmtime_module_t*> moduleCache;
    std::unordered_map<std::string, WasmSession*> sessionCache;

    mutable std::shared_mutex resourceMutex; // Protects both caches

    wasm_config_t* createConfig();
};