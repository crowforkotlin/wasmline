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

namespace wasmline {
    /** Manages compiled Component Model artifacts. */
    class Component {
    public:
        /** Returns the shared Component manager. */
        static Component& getInstance();

        Component(const Component&) = delete;

        Component& operator=(const Component&) = delete;

        /** Loads a Component Model artifact. */
        wasmtime_component_t* load(const std::string& key, const std::string& filePath);

        /** Loads an artifact without cache synchronization. */
        wasmtime_component_t* loadUnsafe(const std::string& key, const std::string& filePath);

        /** Returns a cached component or nullptr. */
        wasmtime_component_t* get(const std::string& key);

        /** Releases one cached component. */
        void release(const std::string& key);

        /** Releases all cached components. */
        void clear();

    private:
        class Impl;

        Component();

        ~Component();

        wasmtime_component_t* compileInternal(const std::string& key, const std::string& filePath);

        std::unique_ptr<Impl> impl_;
    };
} // namespace wasmline
