/**
 * Implements the portable Kotlin/Native logger.
 *
 * Date: 2026-08-19
 * Author: crowforkotlin
 */

#include "logging/NativeLogger.h"
#include <cstdarg>
#include <cstdio>

namespace wasmline {
    namespace {
        void logWithType(FILE *stream, const char *prefix, const char *fmt, va_list args) {
            char buffer[2048];
            vsnprintf(buffer, sizeof(buffer), fmt, args);

            fprintf(stream, "%s%s\n", prefix, buffer);
            fflush(stream);
        }
    }

    void NativeLogI(const char* fmt, ...) {
        va_list args;
        va_start(args, fmt);
        logWithType(stdout, "[WasmLine] ", fmt, args);
        va_end(args);
    }

    void NativeLogE(const char* fmt, ...) {
        va_list args;
        va_start(args, fmt);
        logWithType(stderr, "[WasmLine ERROR] ", fmt, args);
        va_end(args);
    }
}
