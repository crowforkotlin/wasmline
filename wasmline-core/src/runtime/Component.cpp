/**
 * Implements compiled Component Model artifact management.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "wasmline/runtime/Component.h"

#include "wasmline/internal/cache/ArtifactCache.h"
#include "wasmline/internal/logging/NativeLogger.h"
#include "wasmline/internal/wasmtime/WasmtimeMessage.h"
#include "wasmline/runtime/AotLoadPathDiagnostics.h"
#include "wasmline/runtime/Engine.h"

#include <utility>

// Native Component loading is deserialize-only. Any future raw compiler call fails closed and is observable in diagnostics.
#define wasmtime_component_new(...) ::wasmline::AotLoadPathDiagnostics::rejectComponentNew(__VA_ARGS__)

namespace wasmline {
    class Component::Impl {
    public:
        cache::ArtifactCache<wasmtime_component_t> cache{wasmtime_component_delete};
    };

    Component& Component::getInstance() {
        static Component instance;
        return instance;
    }

    Component::Component() : impl_(std::make_unique<Impl>()) {}

    Component::~Component() {
        clear();
    }

    wasmtime_component_t* Component::compileInternal(const std::string& key, const std::string& filePath,
                                                     WasmlineArtifactFormat artifactFormat, ArtifactLoadResult* result) {
        if (artifactFormat == WasmlineArtifactFormat::RAW_WASM) {
            if (result) {
                *result = ArtifactLoadResult::failure(WasmlineErrorCode::ARTIFACT_NOT_COMPATIBLE,
                                                      "Native Component loading requires a precompiled CWASM or PWASM artifact.");
            }
            return nullptr;
        }
        if (artifactFormat != WasmlineArtifactFormat::CWASM && artifactFormat != WasmlineArtifactFormat::PWASM) {
            if (result) {
                *result = ArtifactLoadResult::failure(WasmlineErrorCode::ARTIFACT_NOT_COMPATIBLE,
                                                      "Native Component loading does not support the requested artifact format.");
            }
            return nullptr;
        }

        wasm_engine_t* engine = Engine::getInstance().getEngine();
        if (!engine) {
            if (result) {
                *result =
                    ArtifactLoadResult::failure(WasmlineErrorCode::ENGINE_NOT_INITIALIZED, "Native Wasmline engine is not initialized.");
            }
            LOGE("[Wasmtime] Component -> Engine not initialized.");
            return nullptr;
        }

        wasmtime_component_t* component = nullptr;
        wasmtime_error_t* error = wasmtime_component_deserialize_file(engine, filePath.c_str(), &component);

        if (error) {
            const std::string detail = wasmtime::errorMessage(error);
            if (result) {
                *result = ArtifactLoadResult::failure(WasmlineErrorCode::MODULE_FORMAT_INVALID,
                                                      "Failed to deserialize Component artifact '" + key + "'.",
                                                      std::vector<uint8_t>(detail.begin(), detail.end()));
            }
            wasmtime_error_delete(error);
            return nullptr;
        }
        AotLoadPathDiagnostics::recordComponentDeserializeSuccess();
        return component;
    }

    wasmtime_component_t* Component::load(const std::string& key, const std::string& filePath, WasmlineArtifactFormat artifactFormat,
                                          ArtifactLoadResult* result) {
        ArtifactLoadResult loadResult = ArtifactLoadResult::success();
        wasmtime_component_t* component =
            impl_->cache.load(key, filePath, [this, artifactFormat, &loadResult](const std::string& loadKey, const std::string& path) {
                return compileInternal(loadKey, path, artifactFormat, &loadResult);
            });
        if (!component && loadResult.isSuccess()) {
            loadResult = ArtifactLoadResult::failure(WasmlineErrorCode::MODULE_FORMAT_INVALID, "Native Component artifact load failed.");
        }
        if (result) *result = std::move(loadResult);
        return component;
    }

    wasmtime_component_t* Component::loadUnsafe(const std::string& key, const std::string& filePath, WasmlineArtifactFormat artifactFormat,
                                                ArtifactLoadResult* result) {
        ArtifactLoadResult loadResult = ArtifactLoadResult::success();
        wasmtime_component_t* component = impl_->cache.loadUnsafe(
            key, filePath, [this, artifactFormat, &loadResult](const std::string& loadKey, const std::string& path) {
                return compileInternal(loadKey, path, artifactFormat, &loadResult);
            });
        if (!component && loadResult.isSuccess()) {
            loadResult = ArtifactLoadResult::failure(WasmlineErrorCode::MODULE_FORMAT_INVALID, "Native Component artifact load failed.");
        }
        if (result) *result = std::move(loadResult);
        return component;
    }

    wasmtime_component_t* Component::get(const std::string& key) {
        return impl_->cache.get(key);
    }

    bool Component::release(const std::string& key) {
        return impl_->cache.release(key);
    }

    bool Component::empty() const {
        return impl_->cache.empty();
    }

    void Component::clear() {
        impl_->cache.clear();
    }
} // namespace wasmline
