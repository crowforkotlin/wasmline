/**
 * Implements compiled Component Model artifact management.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "wasmline/runtime/Component.h"

#include <algorithm>
#include <cctype>
#include <vector>

#include "cache/ArtifactCache.h"
#include "io/FileIO.h"
#include "logging/NativeLogger.h"
#include "wasmtime/WasmtimeMessage.h"
#include "wasmline/runtime/Engine.h"

namespace wasmline {
    class Component::Impl {
    public:
        cache::ArtifactCache<wasmtime_component_t> cache{wasmtime_component_delete};
    };

    namespace {
        bool hasSuffixIgnoreCase(const std::string& value, const std::string& suffix) {
            if (value.size() < suffix.size()) return false;
            return std::equal(suffix.rbegin(), suffix.rend(), value.rbegin(), [](char lhs, char rhs) {
                return std::tolower(static_cast<unsigned char>(lhs)) == std::tolower(static_cast<unsigned char>(rhs));
            });
        }

    } // namespace

    Component& Component::getInstance() {
        static Component instance;
        return instance;
    }

    Component::Component() : impl_(std::make_unique<Impl>()) {}

    Component::~Component() {
        clear();
    }

    wasmtime_component_t* Component::compileInternal(const std::string& key, const std::string& filePath) {
        wasm_engine_t* engine = Engine::getInstance().getEngine();
        if (!engine) {
            LOGE("[Wasmtime] Component -> Engine not initialized.");
            return nullptr;
        }

        wasmtime_component_t* component = nullptr;
        wasmtime_error_t* error = nullptr;
        if (hasSuffixIgnoreCase(filePath, ".wasm")) {
#ifdef WASMTIME_FEATURE_COMPILER
            std::vector<uint8_t> data = io::readFile(filePath);
            if (data.empty()) {
                LOGE("[Wasmtime] Component -> Failed to read file: %s", filePath.c_str());
                return nullptr;
            }
            error = wasmtime_component_new(engine, data.data(), data.size(), &component);
#else
            LOGE("[Wasmtime] Component -> Raw component compilation is unavailable: %s", filePath.c_str());
            return nullptr;
#endif
        } else {
            error = wasmtime_component_deserialize_file(engine, filePath.c_str(), &component);
        }

        if (error) {
            const std::string message = wasmtime::errorMessage(error);
            LOGE("[Wasmtime] Component -> Failed to load %s: %s", key.c_str(), message.c_str());
            wasmtime_error_delete(error);
            return nullptr;
        }
        return component;
    }

    wasmtime_component_t* Component::load(const std::string& key, const std::string& filePath) {
        return impl_->cache.load(key, filePath,
                                 [this](const std::string& loadKey, const std::string& path) { return compileInternal(loadKey, path); });
    }

    wasmtime_component_t* Component::loadUnsafe(const std::string& key, const std::string& filePath) {
        return impl_->cache.loadUnsafe(
            key, filePath, [this](const std::string& loadKey, const std::string& path) { return compileInternal(loadKey, path); });
    }

    wasmtime_component_t* Component::get(const std::string& key) {
        return impl_->cache.get(key);
    }

    void Component::release(const std::string& key) {
        impl_->cache.release(key);
    }

    void Component::clear() {
        impl_->cache.clear();
    }
} // namespace wasmline
