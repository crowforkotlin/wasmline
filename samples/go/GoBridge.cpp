// bridge.cpp

// =========================================================
// 【关键步骤】Unity Build 模式
// 包含你项目所有的核心 C++ 实现文件。
// 请根据你的实际目录结构调整 ../ 的层级
// =========================================================
#include "../../wasmtime-cpp/src/WasmApi.cpp"
#include "../../wasmtime-cpp/src/WasmEngine.cpp"
#include "../../wasmtime-cpp/src/WasmModule.cpp"
#include "../../wasmtime-cpp/src/WasmSession.cpp"
#include "../../wasmtime-cpp/src/extensions/FileUtils.cpp"

// 包含 C 接口定义
#include "GoBridge.h"

// =========================================================
// C 接口实现 
// =========================================================
#include <string>
#include <cstring>
#include <cstdlib>

void Bridge_InitEngine() {
    WasmApi::initEngine();
}

void Bridge_ReleaseEngine() {
    WasmApi::releaseEngine();
}

bool Bridge_LoadModule(const char* key, const char* path, bool isJit) {
    return WasmApi::loadModule(std::string(key), std::string(path), isJit);
}

bool Bridge_SaveModuleCache(const char* key, const char* path) {
    return WasmApi::saveModuleCache(std::string(key), std::string(path));
}

void Bridge_ReleaseModule(const char* key) {
    WasmApi::releaseModule(std::string(key));
}

char* Bridge_Call(const char* key, const char* action, const char* inputData, int dataLen) {
    std::string input(inputData, dataLen);
    std::string result = WasmApi::call(std::string(key), std::string(action), input);
    
    char* cstr = (char*)malloc(result.length() + 1);
    if (cstr) {
        std::memcpy(cstr, result.c_str(), result.length());
        cstr[result.length()] = '\0';
    }
    return cstr;
}

void Bridge_FreeString(char* str) {
    if (str) free(str);
}