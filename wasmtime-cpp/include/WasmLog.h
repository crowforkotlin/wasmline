#pragma once
#include <android/log.h>

#define LOG_TAG "WasmNative"

// =========================================================================
// 日志配置区域
// =========================================================================

// 1. 手动开关：如果你想在 Release 包也能看日志，请取消下一行的注释
// #define FORCE_ENABLE_LOGS

// 2. 自动判断：如果是 Debug 模式 (!NDEBUG) 或者 强制开启，则启用日志
#if !defined(NDEBUG) || defined(FORCE_ENABLE_LOGS)
#define WASM_LOG_ENABLED
#endif

// =========================================================================
// 宏定义实现
// =========================================================================

#ifdef WASM_LOG_ENABLED
// 开启状态：直接调用 Android Log
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#else
// 关闭状态：定义为空的 do-while(0) 结构
    // 编译器会直接将其优化移除，零开销 (Zero Cost)
    #define LOGI(...) do {} while(0)
    #define LOGE(...) do {} while(0)
    #define LOGW(...) do {} while(0)
#endif