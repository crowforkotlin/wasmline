#pragma once

#include "Logger.h"
#include <cstdio>
#include <cstdarg>

// 如果你想保留相同的命名空间
namespace wasmline {

    void NativeLogI(const char* fmt, ...) {
        va_list args;
        va_start(args, fmt);
        // 使用标准输出 (stdout)，前面加个 [INFO] 标签方便区分
        printf("[WasmLine][INFO] ");
        vprintf(fmt, args);
        printf("\n"); // 补一个换行符，因为 printf 不会自动换行，而 android_log 会
        va_end(args);
    }

    void NativeLogE(const char* fmt, ...) {
        va_list args;
        va_start(args, fmt);
        // 使用标准错误输出 (stderr)
        fprintf(stderr, "[WasmLine][ERROR] ");
        vfprintf(stderr, fmt, args);
        fprintf(stderr, "\n");
        va_end(args);
    }
}