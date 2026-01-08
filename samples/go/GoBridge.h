#ifndef WASM_BRIDGE_H
#define WASM_BRIDGE_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>

// 初始化与销毁
void Bridge_InitEngine();
void Bridge_ReleaseEngine();

// 模块操作
bool Bridge_LoadModule(const char* key, const char* path, bool isJit);
bool Bridge_SaveModuleCache(const char* key, const char* path);
void Bridge_ReleaseModule(const char* key);

// 调用执行
// 返回值是 C 字符串，需要调用方负责 Free
char* Bridge_Call(const char* key, const char* action, const char* inputData, int dataLen);

// 释放由 C++ 分配传回给 Go 的字符串内存
void Bridge_FreeString(char* str);

#ifdef __cplusplus
}
#endif

#endif // WASM_BRIDGE_H