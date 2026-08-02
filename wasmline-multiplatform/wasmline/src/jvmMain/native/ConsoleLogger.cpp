/**
 * Implements the desktop native logger.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "logging/NativeLogger.h"
#include <cstdio>
#include <cstdarg>

namespace wasmline {

    void NativeLogI(const char* fmt, ...) {
         va_list args;
        va_start(args, fmt);
        fprintf(stdout, "[WasmLine][INFO] ");
        vfprintf(stdout, fmt, args);
        fprintf(stdout, "\n");
        fflush(stdout);
        va_end(args);
    }

    void NativeLogE(const char* fmt, ...) {
        va_list args;
        va_start(args, fmt);
        fprintf(stderr, "[WasmLine][ERROR] ");
        vfprintf(stderr, fmt, args);
        fprintf(stderr, "\n");
        va_end(args);
    }
}
