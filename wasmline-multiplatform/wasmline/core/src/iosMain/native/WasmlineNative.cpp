#include "WasmlineNative.h"
#include "Api.h"
#include "OutboundHandler.h"
#include <string>
#include <cstring>
#include <iostream>

using namespace wasmline;

// --------------------------------------------------------
// 辅助类：C++ -> Kotlin 的回调代理
// --------------------------------------------------------

// --------------------------------------------------------
// C 接口实现
// --------------------------------------------------------
extern "C" {

void wasmline_init_engine() {
    Api::initEngine();
}

void wasmline_release_engine() {
    Api::releaseEngine();
}

bool wasmline_load_module(const char* key, const char* path, bool isJit) {
    return Api::loadModule(std::string(key), std::string(path), isJit);
}

void wasmline_release_module(const char* key) {
    Api::releaseModule(std::string(key));
}

char* wasmline_invoke_inbound(const char* key, const char* action, size_t actionLen, const char* data, size_t dataLen) {
    std::string strKey(key);
    std::string strAction(action, actionLen);
    std::string strData(data, dataLen);

    std::string result = Api::invokeInbound(strKey, strAction.c_str(), strAction.length(), strData.c_str(), strData.length());

    char* cResult = (char*)malloc(result.length() + 1);
    strcpy(cResult, result.c_str());
    return cResult;
}

void wasmline_free_string(char* str) {
    if (str) free(str);
}


}