/**
 * DesktopLogger.cpp
 * Desktop implementation of WasmLogger interfaces.
 * Redirects NativeLogI/E to std::cout and std::cerr.
 *
 * 2025-12-03
 */

#include "WasmLogger.h"
#include <cstdio>
#include <cstdarg>
#include <iostream>

#if WASM_LOGS_ENABLED

void NativeLogI(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    // 绿色前缀 [INFO]
    fprintf(stdout, "\033[32m[INFO] \033[0m");
    vfprintf(stdout, fmt, args);
    fprintf(stdout, "\n");
    va_end(args);
    fflush(stdout);
}

void NativeLogE(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    // 红色前缀 [ERROR]
    fprintf(stderr, "\033[31m[ERROR] \033[0m");
    vfprintf(stderr, fmt, args);
    fprintf(stderr, "\n");
    va_end(args);
    fflush(stderr);
}

#endif