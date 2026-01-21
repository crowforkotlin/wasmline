#pragma once
#include "Logger.h"

#if WASM_LOGS_ENABLED

#include <android/log.h>
#include <cstdarg>

#define TAG "WasmNative"

namespace wasmline {
    void NativeLogI(const char* fmt, ...) {
        va_list args;
        va_start(args, fmt);
        __android_log_vprint(ANDROID_LOG_INFO, TAG, fmt, args);
        va_end(args);
    }

    void NativeLogE(const char* fmt, ...) {
        va_list args;
        va_start(args, fmt);
        __android_log_vprint(ANDROID_LOG_ERROR, TAG, fmt, args);
        va_end(args);
    }
}

#endif