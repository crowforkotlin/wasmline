/**
 * WasmEngine.h
 * Global Wasmtime Engine Manager.
 * Handles the lifecycle of the wasm_engine_t and its configuration.
 *
 * 2025-12-03
 * @author crowforkotlin
 */

#pragma once

#include "wasmtime.h"
#include <mutex>

class WasmEngine {
public:
    /**
     * Access the singleton instance of the Engine wrapper.
     * Guaranteed to be thread-safe by C++11 standards.
     */
    static WasmEngine& getInstance();

    // Delete copy constructor and assignment to enforce Singleton pattern
    WasmEngine(const WasmEngine&) = delete;
    WasmEngine& operator=(const WasmEngine&) = delete;

    /**
     * Initializes the Wasmtime Engine with specific Android optimizations.
     * Must be called once before loading any modules.
     */
    void init();

    /**
     * Releases the Wasmtime Engine.
     * Should be called when the app is terminating or Wasm is no longer needed.
     */
    void release();

    /**
     * Returns the raw wasm_engine_t pointer.
     * Used by WasmModule to compile code and WasmSession to create stores.
     */
    wasm_engine_t* getEngine();

private:
    WasmEngine() = default;
    ~WasmEngine();

    /**
     * Creates the configuration object with critical settings.
     * (GC, SIMD, Signal handlers, etc.)
     */
    wasm_config_t* createConfig();

    // The raw Wasmtime engine handle
    wasm_engine_t* engine = nullptr;

    // Mutex to protect initialization and release phases
    std::mutex engineMutex;
};