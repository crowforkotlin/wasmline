// wasmline-multiplatform/wasmline/core/src/iosSimulatorArm64Main/native/WasmlineNative.h
#ifndef WASMLINE_NATIVE_H
#define WASMLINE_NATIVE_H

#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// 1. 生命周期管理
void wasmline_init_engine();
void wasmline_release_engine();

// 2. 模块加载
bool wasmline_load_module(const char* key, const char* path, bool isJit);
void wasmline_release_module(const char* key);

// 3. 执行调用 (Inbound)
// 返回的 char* 需要调用方负责 free，或者约定由 C++ 管理
// 这里为了简单，假设返回的是 C++ string.c_str() 的拷贝，需要手动释放
char* wasmline_invoke_inbound(const char* key, const char* action, size_t actionLen, const char* data, size_t dataLen);
void wasmline_free_string(char* str); // 用来释放上面返回的字符串


#ifdef __cplusplus
}
#endif

#endif