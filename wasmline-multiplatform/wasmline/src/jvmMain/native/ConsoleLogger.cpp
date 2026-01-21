#pragma once

#include "Logger.h"
#include <cstdio>
#include <cstdarg>

// 如果你想保留相同的命名空间
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