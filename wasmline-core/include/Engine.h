/**
 * Engine.h
 * Global Wasmtime Engine Manager.
 * Handles the lifecycle of the wasm_engine_t and its configuration.
 *
 * 2025-12-03
 * @author crowforkotlin
 */

#pragma once

#include "wasmtime.h"
#include <mutex>

namespace wasmline {
    class Engine {
    public:
        /**
         * Access the singleton instance of the Engine wrapper.
         * Guaranteed to be thread-safe by C++11 standards.
         */
        static Engine& getInstance();

        // Delete copy constructor and assignment to enforce Singleton pattern
        Engine(const Engine&) = delete;
        Engine& operator=(const Engine&) = delete;

        /**
         * Initializes the Wasmtime Engine with specific Android optimizations.
         * Must be called once before loading any modules.
         */
        void init(bool usePulley = true);

        /**
         * Releases the Wasmtime Engine.
         * Should be called when the app is terminating or Wasm is no longer needed.
         */
        void release();

        /**
         * Returns whether the engine has been initialized.
         */
        bool isInitialized();

        /**
         * Returns whether the active engine targets Pulley.
         * Only meaningful when initialized.
         */
        bool isPulley();

        /**
         * Returns the raw wasm_engine_t pointer.
         * Used by Module to compile code and Session to create stores.
         */
        wasm_engine_t* getEngine();

    private:
        Engine() = default;
        ~Engine();

        /**
         * Creates the configuration object with critical settings.
         * (GC, SIMD, Signal handlers, etc.)
         */
        wasm_config_t* createConfig(bool usePulley);

        // The raw Wasmtime engine handle
        wasm_engine_t* engine = nullptr;

        // Mutex to protect initialization and release phases
        std::mutex engineMutex;
    };
} // namespace wasmline