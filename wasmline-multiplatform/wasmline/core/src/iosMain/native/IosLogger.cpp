#include "Logger.h" // 确保引用了定义这些函数的头文件
#include <cstdio>
#include <cstdarg>

namespace wasmline {

    void NativeLogI(const char* fmt, ...) {
        va_list args;
        va_start(args, fmt);
        // iOS 模拟器/真机日志直接输出到 stdout 即可在 Xcode 控制台看到
        printf("💙 [WasmLine] ");
        vprintf(fmt, args);
        printf("\n");
        va_end(args);
    }

    void NativeLogE(const char* fmt, ...) {
        va_list args;
        va_start(args, fmt);
        // 错误输出到 stderr
        fprintf(stderr, "❤️ [WasmLine ERROR] ");
        vfprintf(stderr, fmt, args);
        fprintf(stderr, "\n");
        va_end(args);
    }
}