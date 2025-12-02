/**
 * Log macros for Android platform.
 *
 * Date: 2025-12-02
 * Author: crowforkotlin
 */

#pragma once
#include <android/log.h>

#define LOG_TAG "WasmNative"

// Release builds usually disable logs unless forced
#if !defined(NDEBUG) || defined(FORCE_ENABLE_LOGS)
    #define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
    #define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
    #define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#else
    #define LOGI(...) do {} while(0)
    #define LOGE(...) do {} while(0)
    #define LOGW(...) do {} while(0)
#endif