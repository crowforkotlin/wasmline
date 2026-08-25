/**
 * Declares internal Wasmtime error and trap message helpers.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#pragma once

#include <string>

#include <wasmtime.h>

namespace wasmline::wasmtime {
    std::string errorMessage(const wasmtime_error_t* error);

    std::string trapMessage(const wasm_trap_t* trap);
} // namespace wasmline::wasmtime
