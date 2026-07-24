/**
 * Simple Logger Interface.
 *
 * Logic:
 * 1. If DISABLE_WASM_LOGS is defined -> Logs are STRIPPED (Compiler removes them).
 * 2. If NDEBUG (Release) is defined  -> Logs are STRIPPED.
 * 3. Otherwise (Debug)               -> Logs are ACTIVE.
 *
 * 2025-12-02
 * @author crowforkotlin / crowforkotlin@gmail.com
 */

#pragma once

// Logic to determine if logs should be compiled
#if defined(DISABLE_WASM_LOGS)
#define WASM_LOGS_ENABLED 0
#else
#define WASM_LOGS_ENABLED 1
#endif

// =============================================================
#if WASM_LOGS_ENABLED

namespace wasmline {
    void NativeLogI(const char* fmt, ...);

    void NativeLogE(const char* fmt, ...);

#define LOGI(...) NativeLogI(__VA_ARGS__)
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