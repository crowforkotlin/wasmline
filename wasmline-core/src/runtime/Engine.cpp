/**
 * Implements the global Wasmtime engine manager.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "wasmline/runtime/Engine.h"
#include "wasmline/internal/logging/NativeLogger.h"
#include "wasmline/internal/wasmtime/WasmtimeMessage.h"
#include <array>

namespace wasmline {
    namespace {
        bool configurePulleyTarget(wasm_config_t* conf) {
            const auto targets = (sizeof(void*) == 8) ? std::array<const char*, 2>{"pulley64", "pulley64-unknown-unknown-elf"}
                                                      : std::array<const char*, 2>{"pulley32", "pulley32-unknown-unknown-elf"};

            for (const char* target : targets) {
                wasmtime_error_t* error = wasmtime_config_target_set(conf, target);
                if (!error) {
                    LOGI("[Wasmtime] Engine --> Configured Pulley target: %s", target);
                    return true;
                }

                LOGE("[Wasmtime] Engine --> Failed to set Pulley target to %s: %s", target, wasmtime::errorMessage(error).c_str());
                wasmtime_error_delete(error);
            }

            return false;
        }
    } // namespace

    Engine& Engine::getInstance() {
        static Engine instance;
        return instance;
    }

    Engine::~Engine() {
        release();
    }

    /**
     * Creates the Wasmtime configuration.
     *
     * @param usePulley Selects the Pulley execution backend.
     * @return New Wasmtime configuration or nullptr.
     * @note Signal-based traps are disabled because Android manages process signals.
     * @note Guard pages are disabled to limit virtual memory usage on mobile targets.
     */
    wasm_config_t* Engine::createConfig(bool usePulley) {
        wasm_config_t* conf = wasm_config_new();

        wasmtime_config_wasm_gc_set(conf, true);
        wasmtime_config_gc_support_set(conf, true);
        wasmtime_config_wasm_reference_types_set(conf, true);
        wasmtime_config_wasm_function_references_set(conf, true);
        wasmtime_config_wasm_exceptions_set(conf, true);

        wasmtime_config_wasm_simd_set(conf, false);
        wasmtime_config_wasm_relaxed_simd_set(conf, false);

        wasmtime_config_signals_based_traps_set(conf, false);

        wasmtime_config_memory_guard_size_set(conf, 0);

#ifdef WASMTIME_FEATURE_COMPONENT_MODEL_ASYNC
        wasmtime_config_concurrency_support_set(conf, true);
#endif

        wasmtime_config_max_wasm_stack_set(conf, 512 * 1024);

        if (usePulley) {
            configurePulleyTarget(conf);
        }

#ifdef WASMTIME_FEATURE_COMPILER
        wasmtime_config_cranelift_opt_level_set(conf, WASMTIME_OPT_LEVEL_NONE);
        wasmtime_config_cranelift_debug_verifier_set(conf, false);
#endif

        return conf;
    }

    void Engine::init(bool usePulley) {
        std::lock_guard<std::mutex> lock(engineMutex);
        if (!engine) {
            auto conf = createConfig(usePulley);
            engine = wasm_engine_new_with_config(conf);
            if (engine) {
                LOGI("[Wasmtime] Engine --> Initialized successfully. engine_is_pulley=%s",
                     wasmtime_engine_is_pulley(engine) ? "true" : "false");
            } else {
                LOGE("[Wasmtime] Engine --> Failed to initialize.");
            }
        }
    }

    void Engine::release() {
        std::lock_guard<std::mutex> lock(engineMutex);
        if (engine) {
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

    wasm_engine_t* Engine::getEngine() {
        std::lock_guard<std::mutex> lock(engineMutex);
        return engine;
    }

} // namespace wasmline
