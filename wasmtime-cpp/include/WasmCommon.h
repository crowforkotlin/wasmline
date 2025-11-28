#ifndef WASM_COMMON_H
#define WASM_COMMON_H

#include <string>
#include <vector>
#include <memory>
#include <android/log.h>

// Wasmtime C API
#include "wasm.h"
#include "wasi.h"
#include "wasmtime.h"

#define TAG "WasmCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#endif //WASM_COMMON_H