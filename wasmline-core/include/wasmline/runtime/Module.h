/**
 * Manages Core Wasm artifacts.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#pragma once

#include <memory>
#include <string>
#include <wasmtime.h>

namespace wasmline {
    /** Manages compiled Core Wasm artifacts. */
    class Module {
    public:
        /** Returns the shared Module manager. */
        static Module& getInstance();

        Module(const Module&) = delete;

        Module& operator=(const Module&) = delete;

        /** Loads a Core Wasm artifact. */
        wasmtime_module_t* load(const std::string& key, const std::string& filePath);

        /** Loads an artifact without cache synchronization. */
        wasmtime_module_t* loadUnsafe(const std::string& key, const std::string& filePath);

        /** Returns a cached module or nullptr. */
        wasmtime_module_t* get(const std::string& key);

        /** Releases one cached module. */
        void release(const std::string& key);

        /** Releases all cached modules. */
        void clear();

    private:
        class Impl;

        Module();

        ~Module();

        wasmtime_module_t* compileInternal(const std::string& key, const std::string& filePath);

        std::unique_ptr<Impl> impl_;
    };
} // namespace wasmline
