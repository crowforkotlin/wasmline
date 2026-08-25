/**
 * Implements shared WASI configuration.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "wasmline/internal/wasi/WasiConfig.h"

#include <algorithm>

#include "wasmline/internal/logging/NativeLogger.h"

namespace wasmline::wasi {
    namespace {
        ptrdiff_t writeLog(void* data, const unsigned char* buffer, size_t size) {
            if (buffer && size > 0) {
                const size_t logSize = std::min(size, static_cast<size_t>(1024));
                const char* prefix = data ? static_cast<const char*>(data) : "[Wasmtime-Wasi]";
                LOGI("%s -> %.*s", prefix, static_cast<int>(logSize), reinterpret_cast<const char*>(buffer));
            }
            return static_cast<ptrdiff_t>(size);
        }
    } // namespace

    void configure(wasi_config_t* config, const char* logPrefix) {
        if (!config) return;
        wasi_config_inherit_env(config);
        wasi_config_set_stdout_custom(config, writeLog, const_cast<char*>(logPrefix), nullptr);
        wasi_config_set_stderr_custom(config, writeLog, const_cast<char*>(logPrefix), nullptr);
    }
} // namespace wasmline::wasi
