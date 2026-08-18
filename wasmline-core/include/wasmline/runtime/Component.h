/**
 * Manages compiled Component Model artifacts.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#pragma once

#include <memory>
#include <string>

#include <wasmtime/component/component.h>

#include "wasmline/runtime/WasmlineArtifactFormat.h"

namespace wasmline {
    /** Manages compiled Component Model artifacts. */
    class Component {
    public:
        /** Returns the shared Component manager. */
        static Component& getInstance();

        Component(const Component&) = delete;

        Component& operator=(const Component&) = delete;

        /** Loads a Component Model artifact with an explicit physical format. */
        wasmtime_component_t* load(const std::string& key, const std::string& filePath, WasmlineArtifactFormat artifactFormat);

        /** Loads an artifact with an explicit physical format without cache synchronization. */
        wasmtime_component_t* loadUnsafe(const std::string& key, const std::string& filePath, WasmlineArtifactFormat artifactFormat);

        /** Returns a cached component or nullptr. */
        wasmtime_component_t* get(const std::string& key);

        /** Releases one cached component. */
        bool release(const std::string& key);

        /** Returns whether no loaded components or pending loads remain. */
        bool empty() const;

        /** Releases all cached components. */
        void clear();

    private:
        class Impl;

        Component();

        ~Component();

        wasmtime_component_t* compileInternal(const std::string& key, const std::string& filePath, WasmlineArtifactFormat artifactFormat);

        std::unique_ptr<Impl> impl_;
    };
} // namespace wasmline
