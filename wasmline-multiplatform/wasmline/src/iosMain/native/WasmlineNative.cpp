// wasmline-multiplatform/wasmline/core/src/iosMain/native/WasmlineNative.cpp
#include "WasmlineNative.h"
#include "Api.h"
#include "OutboundHandler.h"
#include <string>
#include <cstring>
#include <iostream>

using namespace wasmline;

class IosOutboundHandler : public OutboundHandler {
private:
    OutboundCallback kotlinCallback;
public:
    IosOutboundHandler(OutboundCallback callback) : kotlinCallback(callback) {}

    std::string onOutboundInvoke(std::string_view action, std::string_view payload) override {
        if (kotlinCallback) {
            char* resultRaw = kotlinCallback(action.data(), action.length(), payload.data(), payload.length());
            if (resultRaw != nullptr) {
                std::string result(resultRaw);
                free(resultRaw);
                return result;
            }
        }
        return "";
    }
};

extern "C" {

void wasmline_init_engine() {
    Api::initEngine();
}

void wasmline_release_engine() {
    Api::releaseEngine();
}

bool wasmline_load_module(const char* key, const char* path, bool isUnsafe) {
    if (isUnsafe) {
        return Api::loadModuleUnsafe(std::string(key), std::string(path));
    } else {
        return Api::loadModule(std::string(key), std::string(path));
    }
}


void wasmline_release_module(const char* key) {
    Api::releaseModule(std::string(key));
}

char* wasmline_invoke_inbound(const char* key,
                              const char* action, size_t actionLen,
                              const void* data, // <--- 对应修改
                              size_t dataLen,
                              size_t* outLen) {

    std::string strKey(key);
    std::string strAction(action, actionLen);
    std::string strData((const char*)data, dataLen);

    std::string resultData = Api::invokeInbound(strKey, strAction.c_str(), strAction.length(), strData.c_str(), strData.length());

    if (resultData.empty()) {
        if (outLen) *outLen = 0;
        return nullptr;
    }

    if (outLen) *outLen = resultData.size();

    char* cResult = (char*)malloc(resultData.size());
    if (cResult == nullptr) return nullptr;

    memcpy(cResult, resultData.data(), resultData.size());

    return cResult;
}

void wasmline_free_memory(char* str) {
    if (str) free(str);
}

void wasmline_set_outbound_handler(const char* key, OutboundCallback callback) {
    // 这里的 make_unique 会调用上面的类
    auto handler = std::make_unique<IosOutboundHandler>(callback);
    Api::setOutboundHandler(std::string(key), std::move(handler));
}

}