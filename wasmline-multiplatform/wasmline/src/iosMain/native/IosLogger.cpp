/**
 * Implements the iOS native logger.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "logging/NativeLogger.h"
#include <cstdarg>
#include <cstdio>
#include <os/log.h>

namespace wasmline {
    namespace {
        void logWithType(os_log_type_t type, const char *prefix, const char *fmt, va_list args) {
            char buffer[2048];
            vsnprintf(buffer, sizeof(buffer), fmt, args);

            FILE *stream = (type == OS_LOG_TYPE_ERROR || type == OS_LOG_TYPE_FAULT) ? stderr : stdout;
            fprintf(stream, "%s%s\n", prefix, buffer);
            fflush(stream);

            os_log_with_type(OS_LOG_DEFAULT, type, "%{public}s%{public}s", prefix, buffer);
        }
    }

    void NativeLogI(const char* fmt, ...) {
        va_list args;
        va_start(args, fmt);
        logWithType(OS_LOG_TYPE_INFO, "[WasmLine] ", fmt, args);
        va_end(args);
    }

    void NativeLogE(const char* fmt, ...) {
        va_list args;
        va_start(args, fmt);
        logWithType(OS_LOG_TYPE_ERROR, "[WasmLine ERROR] ", fmt, args);
        va_end(args);
    }
}
