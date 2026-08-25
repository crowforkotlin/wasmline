/**
 * Declares internal shared WASI configuration.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#pragma once

#include <cstddef>

#include <wasmtime.h>

namespace wasmline::wasi {
    void configure(wasi_config_t* config, const char* logPrefix);
}
