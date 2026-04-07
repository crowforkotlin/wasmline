// wasmline-multiplatform/wasmline/core/src/iosMain/native/WasmlineNative.h
#ifndef WASMLINE_NATIVE_H
#define WASMLINE_NATIVE_H

#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// 1. 引擎生命周期
void wasmline_init_engine();
void wasmline_release_engine();

// 2. 模块加载
bool wasmline_load_module(const char* key, const char* path, bool isUnsafe);

// 3. 释放模块
void wasmline_release_module(const char* key);

// 4. 执行调用 (Inbound)
char* wasmline_invoke_inbound(const char* key,
                              const char* action, size_t actionLen,
                              const void* data, // <--- 改成 void*
                              size_t dataLen,
                              size_t* outLen);

// 5. 释放内存
void wasmline_free_memory(char* ptr);

// 6. Outbound 回调
typedef char* (*OutboundCallback)(const char* action, size_t actionLen, const char* payload, size_t payloadLen);
void wasmline_set_outbound_handler(const char* key, OutboundCallback callback);

#ifdef __cplusplus
}
#endif

#endif