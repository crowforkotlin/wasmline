/**
 * Defines the native logging interface.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#pragma once

#if defined(DISABLE_WASM_LOGS)
#define WASM_LOGS_ENABLED 0
#else
#define WASM_LOGS_ENABLED 1
#endif

#if WASM_LOGS_ENABLED

namespace wasmline {
    void NativeLogI(const char* fmt, ...);

    void NativeLogE(const char* fmt, ...);

/** Routine logs require ENABLE_WASM_INFO_LOGS. Error logs remain enabled. */
#if defined(ENABLE_WASM_INFO_LOGS)
#define LOGI(...) NativeLogI(__VA_ARGS__)
#else
#define LOGI(...)                                                                                                                          \
    do {                                                                                                                                   \
    } while (0)
#endif

#define LOGE(...) NativeLogE(__VA_ARGS__)
} // namespace wasmline

#else

#define LOGI(...)                                                                                                                          \
    do {                                                                                                                                   \
    } while (0)
#define LOGE(...)                                                                                                                          \
    do {                                                                                                                                   \
    } while (0)

#endif
