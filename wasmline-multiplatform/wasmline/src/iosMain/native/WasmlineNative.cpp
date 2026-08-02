/**
 * Implements the iOS C bridge for the Wasmline native API.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
#include "WasmlineNative.h"
#include "wasmline/api/Api.h"
#include "wasmline/invocation/TypedInvocationCodec.h"
#include "wasmline/protocol/WasmlineProtocol.h"
#include "wasmline/runtime/OutboundHandler.h"
#include <memory>
#include <string>
#include <cstring>
#include <iostream>

using namespace wasmline;

/** Forwards outbound calls to the Kotlin callback. */
class IosOutboundHandler : public OutboundHandler {
private:
    OutboundCallback kotlinCallback;
public:
    /** Creates a handler for the Kotlin callback. */
    IosOutboundHandler(OutboundCallback callback) : kotlinCallback(callback) {}

    /** Sends an outbound call to Kotlin. */
    std::string onOutboundInvoke(std::string_view action, std::string_view payload) override {
        if (kotlinCallback) {
            size_t resultLength = 0;
            char* resultRaw = kotlinCallback(action.data(), action.length(), payload.data(), payload.length(), &resultLength);
            if (resultRaw != nullptr) {
                std::string result(resultRaw, resultLength);
                free(resultRaw);
                return result;
            }
        }
        return WasmlineResponseCodec::failure(
            WasmlineErrorCode::ACTION_NOT_BOUND,
            "No Wasmline outbound action is bound.");
    }
};

extern "C" {

void wasmline_init_engine() {
    Api::initEngine();
}

void wasmline_warmup_engine(bool usePulley) {
    Api::warmupEngine(usePulley);
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

bool wasmline_load_component(const char* key, const char* path, bool isUnsafe) {
    if (isUnsafe) {
        return Api::loadComponentUnsafe(std::string(key), std::string(path));
    }
    return Api::loadComponent(std::string(key), std::string(path));
}


void wasmline_release_module(const char* key) {
    Api::releaseModule(std::string(key));
}

static char* invokeTyped(const char* key, const char* exportName, size_t exportNameLen, const void* data, size_t dataLen,
                         size_t* outLen, TypedInvocationKind kind) {
    if (outLen) *outLen = 0;
    InvocationResult result = InvocationResult::failure(
        WasmlineErrorCode::TRANSPORT_FAILURE,
        "iOS typed invocation received an invalid native input.");

    const bool inputIsValid = key != nullptr && (exportNameLen == 0 || exportName != nullptr) &&
                              (dataLen == 0 || data != nullptr);
    if (inputIsValid) {
        const std::string input = dataLen == 0 ? std::string() : std::string(static_cast<const char*>(data), dataLen);
        const std::string exportValue = exportNameLen == 0 ? std::string() : std::string(exportName, exportNameLen);
        std::string error;
        if (kind == TypedInvocationKind::RAW) {
            std::vector<RawValue> arguments;
            if (!TypedInvocationCodec::decodeRawArguments(input, &arguments, &error)) {
                result = InvocationResult::failure(WasmlineErrorCode::INVALID_PAYLOAD,
                                                   error.empty() ? "Raw invocation payload is invalid." : error);
            } else {
                result = Api::invokeRaw(std::string(key), exportValue, arguments);
            }
        } else {
            std::vector<ComponentValue> arguments;
            if (!TypedInvocationCodec::decodeComponentArguments(input, &arguments, &error)) {
                result = InvocationResult::failure(WasmlineErrorCode::INVALID_PAYLOAD,
                                                   error.empty() ? "Component invocation payload is invalid." : error);
            } else {
                result = Api::invokeComponent(std::string(key), exportValue, arguments);
            }
        }
    }

    const std::vector<uint8_t> encoded = TypedInvocationCodec::encodeResult(result, kind);
    if (outLen) *outLen = encoded.size();
    if (encoded.empty()) return nullptr;
    char* output = static_cast<char*>(malloc(encoded.size()));
    if (!output) {
        if (outLen) *outLen = 0;
        return nullptr;
    }
    memcpy(output, encoded.data(), encoded.size());
    return output;
}

char* wasmline_invoke_raw(const char* key, const char* exportName, size_t exportNameLen, const void* data, size_t dataLen,
                          size_t* outLen) {
    return invokeTyped(key, exportName, exportNameLen, data, dataLen, outLen, TypedInvocationKind::RAW);
}

char* wasmline_invoke_component(const char* key, const char* exportName, size_t exportNameLen, const void* data,
                                size_t dataLen, size_t* outLen) {
    return invokeTyped(key, exportName, exportNameLen, data, dataLen, outLen, TypedInvocationKind::COMPONENT);
}

char* wasmline_invoke_inbound(const char* key,
                              const char* action, size_t actionLen,
                              const void* data,
                              size_t dataLen,
                              size_t* outLen) {
    if (outLen) *outLen = 0;
    std::string resultData;
    if (!key || (actionLen > 0 && !action) || (dataLen > 0 && !data)) {
        resultData = WasmlineResponseCodec::failure(
            WasmlineErrorCode::TRANSPORT_FAILURE,
            "iOS inbound invocation received an invalid native input.");
    } else {
        std::string strKey(key);
        std::string strAction = actionLen == 0 ? std::string() : std::string(action, actionLen);
        std::string strData = dataLen == 0 ? std::string() : std::string(static_cast<const char*>(data), dataLen);
        resultData = Api::invokeInbound(strKey, strAction.c_str(), strAction.length(), strData.c_str(), strData.length());
    }

    if (resultData.empty()) {
        return nullptr;
    }

    if (outLen) *outLen = resultData.size();

    char* cResult = (char*)malloc(resultData.size());
    if (cResult == nullptr) {
        if (outLen) *outLen = 0;
        return nullptr;
    }

    memcpy(cResult, resultData.data(), resultData.size());

    return cResult;
}

void wasmline_free_memory(char* str) {
    if (str) free(str);
}

void wasmline_set_outbound_handler(const char* key, OutboundCallback callback) {
    std::unique_ptr<OutboundHandler> handler(new IosOutboundHandler(callback));
    Api::setOutboundHandler(std::string(key), std::move(handler));
}

}
