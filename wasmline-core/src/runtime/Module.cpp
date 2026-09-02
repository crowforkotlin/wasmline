/**
 * Implements core Wasm artifact management.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "wasmline/runtime/Module.h"

#include "wasmline/internal/cache/ArtifactCache.h"
#include "wasmline/internal/logging/NativeLogger.h"
#include "wasmline/internal/wasmtime/WasmtimeMessage.h"
#include "wasmline/runtime/AotLoadPathDiagnostics.h"
#include "wasmline/runtime/Engine.h"

#include <utility>

// Native Core loading is deserialize-only. Any future raw compiler call fails closed and is observable in diagnostics.
#define wasmtime_module_new(...) ::wasmline::AotLoadPathDiagnostics::rejectModuleNew(__VA_ARGS__)

namespace wasmline {
    class Module::Impl {
    public:
        cache::ArtifactCache<wasmtime_module_t> cache{wasmtime_module_delete};
    };

    Module::Module() : impl_(std::make_unique<Impl>()) {}

    Module& Module::getInstance() {
        static Module instance;
        return instance;
    }

    Module::~Module() {
        clear();
    }

    /**
     * Loads a Core Wasm artifact from a source or precompiled file.
     *
     * @param filePath Artifact file path.
     * @return Compiled module or nullptr.
     * @note The cache lock is not held while the artifact is compiled.
     */
    wasmtime_module_t* Module::compileInternal(const std::string& key, const std::string& filePath, WasmlineArtifactFormat artifactFormat,
                                               ArtifactLoadResult* result) {
        if (artifactFormat == WasmlineArtifactFormat::RAW_WASM) {
            if (result) {
                *result = ArtifactLoadResult::failure(WasmlineErrorCode::ARTIFACT_NOT_COMPATIBLE,
                                                      "Native Core Wasm loading requires a precompiled CWASM or PWASM artifact.");
            }
            return nullptr;
        }
        if (artifactFormat != WasmlineArtifactFormat::CWASM && artifactFormat != WasmlineArtifactFormat::PWASM) {
            if (result) {
                *result = ArtifactLoadResult::failure(WasmlineErrorCode::ARTIFACT_NOT_COMPATIBLE,
                                                      "Native Core Wasm loading does not support the requested artifact format.");
            }
            return nullptr;
        }

        wasm_engine_t* engine = Engine::getInstance().getEngine();
        if (!engine) {
            if (result) {
                *result =
                    ArtifactLoadResult::failure(WasmlineErrorCode::ENGINE_NOT_INITIALIZED, "Native Wasmline engine is not initialized.");
            }
            LOGE("[Wasmtime] Module --> Engine not initialized.");
            return nullptr;
        }

        wasmtime_module_t* module = nullptr;
        LOGI("[Wasmtime] Module --> Deserializing precompiled artifact for %s", filePath.c_str());
        wasmtime_error_t* error = wasmtime_module_deserialize_file(engine, filePath.c_str(), &module);

        if (error) {
            const std::string detail = wasmtime::errorMessage(error);
            if (result) {
                *result = ArtifactLoadResult::failure(WasmlineErrorCode::MODULE_FORMAT_INVALID,
                                                      "Failed to deserialize Core Wasm artifact '" + key + "'.",
                                                      std::vector<uint8_t>(detail.begin(), detail.end()));
            }
            wasmtime_error_delete(error);
            return nullptr;
        }

        AotLoadPathDiagnostics::recordCoreDeserializeSuccess();
        return module;
    }

    wasmtime_module_t* Module::load(const std::string& key, const std::string& filePath, WasmlineArtifactFormat artifactFormat,
                                    ArtifactLoadResult* result) {
        ArtifactLoadResult loadResult = ArtifactLoadResult::success();
        wasmtime_module_t* module =
            impl_->cache.load(key, filePath, [this, artifactFormat, &loadResult](const std::string& loadKey, const std::string& path) {
                return compileInternal(loadKey, path, artifactFormat, &loadResult);
            });
        if (!module && loadResult.isSuccess()) {
            loadResult = ArtifactLoadResult::failure(WasmlineErrorCode::MODULE_FORMAT_INVALID, "Native Core Wasm module load failed.");
        }
        if (result) *result = std::move(loadResult);
        if (module) LOGI("[Wasmtime] Module --> Loaded and cached: %s", key.c_str());
        return module;
    }

    wasmtime_module_t* Module::loadUnsafe(const std::string& key, const std::string& filePath, WasmlineArtifactFormat artifactFormat,
                                          ArtifactLoadResult* result) {
        ArtifactLoadResult loadResult = ArtifactLoadResult::success();
        wasmtime_module_t* module = impl_->cache.loadUnsafe(
            key, filePath, [this, artifactFormat, &loadResult](const std::string& loadKey, const std::string& path) {
                return compileInternal(loadKey, path, artifactFormat, &loadResult);
            });
        if (!module && loadResult.isSuccess()) {
            loadResult = ArtifactLoadResult::failure(WasmlineErrorCode::MODULE_FORMAT_INVALID, "Native Core Wasm module load failed.");
        }
        if (result) *result = std::move(loadResult);
        if (module) LOGI("[Wasmtime] Module (Unsafe) --> Loaded and cached: %s", key.c_str());
        return module;
    }

    wasmtime_module_t* Module::get(const std::string& key) {
        return impl_->cache.get(key);
    }

    bool Module::release(const std::string& key) {
        const bool released = impl_->cache.release(key);
        if (released) LOGI("[Wasmtime] Module --> Released module key: %s", key.c_str());
        return released;
    }

    bool Module::empty() const {
        return impl_->cache.empty();
    }

    void Module::clear() {
        impl_->cache.clear();
        LOGI("[Wasmtime] Module --> All modules released.");
    }
} // namespace wasmline
