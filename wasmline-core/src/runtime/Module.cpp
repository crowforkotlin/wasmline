/**
 * Implements core Wasm artifact management.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "wasmline/runtime/Module.h"

#include "cache/ArtifactCache.h"
#include "logging/NativeLogger.h"
#include "wasmtime/WasmtimeMessage.h"
#include "wasmline/runtime/AotLoadPathDiagnostics.h"
#include "wasmline/runtime/Engine.h"

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
     * @param key Artifact identifier.
     * @param filePath Artifact file path.
     * @return Compiled module or nullptr.
     * @note The cache lock is not held while the artifact is compiled.
     */
    wasmtime_module_t* Module::compileInternal(const std::string& key, const std::string& filePath, WasmlineArtifactFormat artifactFormat) {
        if (artifactFormat == WasmlineArtifactFormat::RAW_WASM) {
            LOGE("[Wasmtime] Module --> Raw Core Wasm is not accepted on native. Precompile to CWASM/PWASM: %s", filePath.c_str());
            return nullptr;
        }
        if (artifactFormat != WasmlineArtifactFormat::CWASM && artifactFormat != WasmlineArtifactFormat::PWASM) {
            LOGE("[Wasmtime] Module --> Unsupported artifact format for %s", filePath.c_str());
            return nullptr;
        }

        wasm_engine_t* engine = Engine::getInstance().getEngine();
        if (!engine) {
            LOGE("[Wasmtime] Module --> Engine not initialized.");
            return nullptr;
        }

        wasmtime_module_t* module = nullptr;
        LOGI("[Wasmtime] Module --> Deserializing precompiled artifact for %s", filePath.c_str());
        wasmtime_error_t* error = wasmtime_module_deserialize_file(engine, filePath.c_str(), &module);

        if (error) {
            LOGE("[Wasmtime] Module --> Error loading module %s: %s", key.c_str(), wasmtime::errorMessage(error).c_str());
            wasmtime_error_delete(error);
            return nullptr;
        }

        AotLoadPathDiagnostics::recordCoreDeserializeSuccess();
        return module;
    }

    wasmtime_module_t* Module::load(const std::string&, const std::string& filePath) {
        LOGE("[Wasmtime] Module --> Explicit artifact format is required: %s", filePath.c_str());
        return nullptr;
    }

    wasmtime_module_t* Module::load(const std::string& key, const std::string& filePath, WasmlineArtifactFormat artifactFormat) {
        wasmtime_module_t* module =
            impl_->cache.load(key, filePath, [this, artifactFormat](const std::string& loadKey, const std::string& path) {
                return compileInternal(loadKey, path, artifactFormat);
            });
        if (module) LOGI("[Wasmtime] Module --> Loaded and cached: %s", key.c_str());
        return module;
    }

    wasmtime_module_t* Module::loadUnsafe(const std::string&, const std::string& filePath) {
        LOGE("[Wasmtime] Module --> Explicit artifact format is required: %s", filePath.c_str());
        return nullptr;
    }

    wasmtime_module_t* Module::loadUnsafe(const std::string& key, const std::string& filePath, WasmlineArtifactFormat artifactFormat) {
        wasmtime_module_t* module =
            impl_->cache.loadUnsafe(key, filePath, [this, artifactFormat](const std::string& loadKey, const std::string& path) {
                return compileInternal(loadKey, path, artifactFormat);
            });
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

    void Module::clear() {
        impl_->cache.clear();
        LOGI("[Wasmtime] Module --> All modules released.");
    }
} // namespace wasmline
