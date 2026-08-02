/**
 * Implements core Wasm artifact management.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "wasmline/runtime/Module.h"

#include <algorithm>
#include <cctype>
#include <vector>

#include "cache/ArtifactCache.h"
#include "io/FileIO.h"
#include "logging/NativeLogger.h"
#include "wasmtime/WasmtimeMessage.h"
#include "wasmline/runtime/Engine.h"

namespace wasmline {
    class Module::Impl {
    public:
        cache::ArtifactCache<wasmtime_module_t> cache{wasmtime_module_delete};
    };

    namespace {
        bool hasSuffixIgnoreCase(const std::string& value, const std::string& suffix) {
            if (value.size() < suffix.size()) return false;
            return std::equal(suffix.rbegin(), suffix.rend(), value.rbegin(), [](char lhs, char rhs) {
                return std::tolower(static_cast<unsigned char>(lhs)) == std::tolower(static_cast<unsigned char>(rhs));
            });
        }
    } // namespace

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
    wasmtime_module_t* Module::compileInternal(const std::string& key, const std::string& filePath) {
        wasm_engine_t* engine = Engine::getInstance().getEngine();
        if (!engine) {
            LOGE("[Wasmtime] Module --> Engine not initialized.");
            return nullptr;
        }

        const bool rawWasm = hasSuffixIgnoreCase(filePath, ".wasm");

        wasmtime_module_t* module = nullptr;
        wasmtime_error_t* error = nullptr;

        if (rawWasm) {
#ifdef WASMTIME_FEATURE_COMPILER
            std::vector<uint8_t> data = io::readFile(filePath);
            if (data.empty()) {
                LOGE("[Wasmtime] Module --> Failed to read file: %s", filePath.c_str());
                return nullptr;
            }

            LOGI("[Wasmtime] Module --> Compiling raw wasm module for %s", filePath.c_str());
            error = wasmtime_module_new(engine, reinterpret_cast<const uint8_t*>(data.data()), data.size(), &module);
#else
            LOGE("[Wasmtime] Module --> Raw wasm compilation not available (no compiler). Use precompiled .pwasm artifacts. file=%s",
                 filePath.c_str());
            return nullptr;
#endif
        } else {
            LOGI("[Wasmtime] Module --> Deserializing precompiled artifact for %s", filePath.c_str());
            error = wasmtime_module_deserialize_file(engine, filePath.c_str(), &module);
        }

        if (error) {
            LOGE("[Wasmtime] Module --> Error loading module %s: %s", key.c_str(), wasmtime::errorMessage(error).c_str());
            wasmtime_error_delete(error);
            return nullptr;
        }

        return module;
    }

    wasmtime_module_t* Module::load(const std::string& key, const std::string& filePath) {
        wasmtime_module_t* module = impl_->cache.load(
            key, filePath, [this](const std::string& loadKey, const std::string& path) { return compileInternal(loadKey, path); });
        if (module) LOGI("[Wasmtime] Module --> Loaded and cached: %s", key.c_str());
        return module;
    }

    wasmtime_module_t* Module::loadUnsafe(const std::string& key, const std::string& filePath) {
        wasmtime_module_t* module = impl_->cache.loadUnsafe(
            key, filePath, [this](const std::string& loadKey, const std::string& path) { return compileInternal(loadKey, path); });
        if (module) LOGI("[Wasmtime] Module (Unsafe) --> Loaded and cached: %s", key.c_str());
        return module;
    }

    wasmtime_module_t* Module::get(const std::string& key) {
        return impl_->cache.get(key);
    }

    void Module::release(const std::string& key) {
        impl_->cache.release(key);
        LOGI("[Wasmtime] Module --> Released module key: %s", key.c_str());
    }

    void Module::clear() {
        impl_->cache.clear();
        LOGI("[Wasmtime] Module --> All modules released.");
    }
} // namespace wasmline
