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
                                                     WasmlineArtifactFormat artifactFormat) {
        if (artifactFormat == WasmlineArtifactFormat::RAW_WASM) {
            LOGE("[Wasmtime] Component -> Raw Component Wasm is not accepted on native. Precompile to CWASM/PWASM: %s", filePath.c_str());
            return nullptr;
        }
        if (artifactFormat != WasmlineArtifactFormat::CWASM && artifactFormat != WasmlineArtifactFormat::PWASM) {
            LOGE("[Wasmtime] Component -> Unsupported artifact format for %s", filePath.c_str());
            return nullptr;
        }

        wasm_engine_t* engine = Engine::getInstance().getEngine();
        if (!engine) {
            LOGE("[Wasmtime] Component -> Engine not initialized.");
            return nullptr;
        }

        wasmtime_component_t* component = nullptr;
        wasmtime_error_t* error = wasmtime_component_deserialize_file(engine, filePath.c_str(), &component);

        if (error) {
            const std::string message = wasmtime::errorMessage(error);
            LOGE("[Wasmtime] Component -> Failed to load %s: %s", key.c_str(), message.c_str());
            wasmtime_error_delete(error);
            return nullptr;
        }
        AotLoadPathDiagnostics::recordComponentDeserializeSuccess();
        return component;
    }

    wasmtime_component_t* Component::load(const std::string& key, const std::string& filePath, WasmlineArtifactFormat artifactFormat) {
        return impl_->cache.load(key, filePath, [this, artifactFormat](const std::string& loadKey, const std::string& path) {
            return compileInternal(loadKey, path, artifactFormat);
        });
    }

    wasmtime_component_t* Component::loadUnsafe(const std::string& key, const std::string& filePath,
                                                WasmlineArtifactFormat artifactFormat) {
        return impl_->cache.loadUnsafe(key, filePath, [this, artifactFormat](const std::string& loadKey, const std::string& path) {
            return compileInternal(loadKey, path, artifactFormat);
        });
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
