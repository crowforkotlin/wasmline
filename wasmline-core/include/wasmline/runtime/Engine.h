/**
 * Manages the global Wasmtime engine.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#pragma once

#include <wasmtime.h>
#include <mutex>

namespace wasmline {
    /** Manages the global Wasmtime engine. */
    class Engine {
    public:
        /** Returns the shared engine manager. */
        static Engine& getInstance();

        Engine(const Engine&) = delete;
        Engine& operator=(const Engine&) = delete;

        /** Initializes the Wasmtime engine. */
        void init(bool usePulley = true);

        /** Releases the Wasmtime engine. */
        void release();

        /** Returns whether the engine has been initialized. */
        bool isInitialized();

        /** Returns whether the active engine uses Pulley. */
        bool isPulley();

        /** Returns the raw Wasmtime engine handle. */
        wasm_engine_t* getEngine();

    private:
        Engine() = default;
        ~Engine();

        /** Creates the Wasmtime configuration. */
        wasm_config_t* createConfig(bool usePulley);

        wasm_engine_t* engine = nullptr;

        std::mutex engineMutex;
    };
} // namespace wasmline
