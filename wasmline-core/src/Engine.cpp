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
#include <array>

namespace wasmline {
    namespace {
        bool configurePulleyTarget(wasm_config_t *conf) {
            const auto targets = (sizeof(void*) == 8)
                ? std::array<const char*, 2>{"pulley64", "pulley64-unknown-unknown-elf"}
                : std::array<const char*, 2>{"pulley32", "pulley32-unknown-unknown-elf"};

            for (const char *target : targets) {
                wasmtime_error_t *error = wasmtime_config_target_set(conf, target);
                if (!error) {
                    LOGI("[Wasmtime] Engine --> Configured Pulley target: %s", target);
                    return true;
                }

                wasm_byte_vec_t msg;
                wasmtime_error_message(error, &msg);
                LOGE("[Wasmtime] Engine --> Failed to set Pulley target to %s: %s", target, msg.data);
                wasm_byte_vec_delete(&msg);
                wasmtime_error_delete(error);
            }

            return false;
        }
    }

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
    wasm_config_t *Engine::createConfig(bool usePulley) {
        wasm_config_t *conf = wasm_config_new();

        // Feature Flags for Kotlin/Wasm support
        wasmtime_config_wasm_gc_set(conf, true);
        wasmtime_config_gc_support_set(conf, true);
        wasmtime_config_wasm_reference_types_set(conf, true);
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

#ifdef WASMTIME_FEATURE_COMPONENT_MODEL_ASYNC
        // Enable concurrency support to match the compiler-side configuration.
        // The CLI compile step enables the component-model-async cargo feature,
        // which sets concurrency_support=true in the serialized .pwasm artifact.
        // The host must match this or deserialization will fail with:
        // "Module was compiled with concurrency support but it is not enabled for the host"
        wasmtime_config_concurrency_support_set(conf, true);
#endif

        // Set max stack size (512KB is usually sufficient for mobile logic)
        wasmtime_config_max_wasm_stack_set(conf, 512 * 1024);

        if (usePulley) {
            configurePulleyTarget(conf);
        }

#ifdef WASMTIME_FEATURE_COMPILER
        // Compiler Optimization Strategy: Optimize for Speed and Binary Size
        wasmtime_config_cranelift_opt_level_set(conf, WASMTIME_OPT_LEVEL_NONE);
        wasmtime_config_cranelift_debug_verifier_set(conf, false);
#endif

        return conf;
    }

    // Initialize the Engine
    void Engine::init(bool usePulley) {
        std::lock_guard<std::mutex> lock(engineMutex);
        if (!engine) {
            auto conf = createConfig(usePulley);
            // Create the engine with the configuration
            engine = wasm_engine_new_with_config(conf);
            if (engine) {
                LOGI(
                    "[Wasmtime] Engine --> Initialized successfully. engine_is_pulley=%s",
                    wasmtime_engine_is_pulley(engine) ? "true" : "false"
                );
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

    bool Engine::isInitialized() {
        std::lock_guard<std::mutex> lock(engineMutex);
        return engine != nullptr;
    }

    bool Engine::isPulley() {
        std::lock_guard<std::mutex> lock(engineMutex);
        return engine != nullptr && wasmtime_engine_is_pulley(engine);
    }

    // Getter for the raw engine pointer
    wasm_engine_t *Engine::getEngine() {
        std::lock_guard<std::mutex> lock(engineMutex);
        return engine;
    }
}
