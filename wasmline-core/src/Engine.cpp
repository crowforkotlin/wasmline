/**
 * Engine.native
 * Implementation of the Wasmtime Engine Manager.
 * Contains critical Android-specific configurations.
 *
 * 2025-12-03
 * @author crowforkotlin
 */

#include "Engine.h"
#include "Logger.h"

namespace wasmline {

    // Singleton Instance Accessor
    Engine &Engine::getInstance() {
        static Engine instance;
        return instance;
    }

    // Destructor
    Engine::~Engine() {
        release();
    }

    /**
     * Creates and configures the Wasm Config object.
     *
     * Critical Android Settings:
     * 1. Signals-based traps DISABLED: To prevent conflicts with Android ART signal handlers (SIGSEGV).
     * 2. Memory Guard Size = 0: To prevent VSS (Virtual Set Size) OOM on 32-bit or limited devices.
     * 3. GC / Exceptions: Enabled for Kotlin/Wasm support.
     */
    wasm_config_t *Engine::createConfig() {
        wasm_config_t *conf = wasm_config_new();

        // Feature Flags for Kotlin/Wasm support
        wasmtime_config_wasm_gc_set(conf, true);
        wasmtime_config_wasm_function_references_set(conf, true);
        wasmtime_config_wasm_exceptions_set(conf, true);

        // Optimization: Disable SIMD if not strictly needed (improves compatibility)
        // Note: Kept as requested in requirements.
        wasmtime_config_wasm_simd_set(conf, false);
        wasmtime_config_wasm_relaxed_simd_set(conf, false);

        // [CRITICAL] Disable signal handlers to avoid crash conflicts with Android Runtime (ART)
        wasmtime_config_signals_based_traps_set(conf, false);

        // [CRITICAL] Set guard pages to 0 to minimize Virtual Memory usage (prevents OOM)
        wasmtime_config_memory_guard_size_set(conf, 0);

        // Set max stack size (512KB is usually sufficient for mobile logic)
        wasmtime_config_max_wasm_stack_set(conf, 512 * 1024);

        // Automatically determine whether it is a 64-bit or 32-bit system
        // Most Android/iOS are Little Endian (little endian)
        const char* target_triple = (sizeof(void*) == 8) ? "pulley64" : "pulley32";

        wasmtime_error_t *error = wasmtime_config_target_set(conf, target_triple);
        if (error) {
            wasm_byte_vec_t msg;
            wasmtime_error_message(error, &msg);
            LOGE("[Wasmtime] Engine --> Failed to set Pulley target to %s: %s", target_triple, msg.data);
            wasm_byte_vec_delete(&msg);
            wasmtime_error_delete(error);
            // Note: If this fails, it usually means that libwasmtime.so was compiled without the pulley feature.
        } else {
            LOGI("[Wasmtime] Engine --> Configured for Pulley Interpreter (%s)", target_triple);
        }

        // Compiler Optimization Strategy: Optimize for Speed and Binary Size
        wasmtime_config_cranelift_opt_level_set(conf, WASMTIME_OPT_LEVEL_NONE);
        wasmtime_config_cranelift_debug_verifier_set(conf, false);

        return conf;
    }

    // Initialize the Engine
    void Engine::init() {
        std::lock_guard<std::mutex> lock(engineMutex);
        if (!engine) {
            auto conf = createConfig();
            // Create the engine with the configuration
            engine = wasm_engine_new_with_config(conf);
            if (engine) {
                LOGI("[Wasmtime] Engine --> Initialized successfully.");
            } else {
                LOGE("[Wasmtime] Engine --> Failed to initialize.");
            }
        }
    }

    // Release the Engine
    void Engine::release() {
        std::lock_guard<std::mutex> lock(engineMutex);
        if (engine) {
            // Free the engine memory
            wasm_engine_delete(engine);
            engine = nullptr;
            LOGI("[Wasmtime] Engine --> Released.");
        }
    }

    // Getter for the raw engine pointer
    wasm_engine_t *Engine::getEngine() {
        std::lock_guard<std::mutex> lock(engineMutex);
        return engine;
    }
}