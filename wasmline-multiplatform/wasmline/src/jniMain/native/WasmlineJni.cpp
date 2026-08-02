/**
 * Provides the JNI bridge for the Wasmline native API.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

/**
 * Provides the floating-point initialization symbol required by the Wasmtime
 * static library on non-MSVC Windows targets.
 */
#if defined(_WIN32) || defined(_WIN64)
extern "C" {
    int _fltused = 0;
}
#endif

#include "WasmlineJni.h"
#include <limits>
#include <vector>
#include "wasmline/invocation/TypedInvocationCodec.h"
#include "wasmline/protocol/WasmlineProtocol.h"
#include "logging/NativeLogger.h"

static jbyteArray newByteArray(JNIEnv *env, const void *data, size_t size) {
    if (size > static_cast<size_t>(std::numeric_limits<jsize>::max())) return nullptr;
    jbyteArray array = env->NewByteArray(static_cast<jsize>(size));
    if (array && size > 0) {
        env->SetByteArrayRegion(array, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(data));
    }
    return array;
}

static jbyteArray newByteArray(JNIEnv *env, const std::string &data) {
    return newByteArray(env, data.data(), data.size());
}

static jbyteArray newByteArray(JNIEnv *env, const std::vector<uint8_t> &data) {
    return newByteArray(env, data.data(), data.size());
}

static jbyteArray transportFailure(JNIEnv *env, const char *message) {
    return newByteArray(env, wasmline::WasmlineResponseCodec::failure(
                               wasmline::WasmlineErrorCode::TRANSPORT_FAILURE, message));
}

extern "C" {

JNIEXPORT void JNICALL
Java_crow_wasmline_Wasmline_nativeWarmup(JNIEnv *env, jclass thiz, jboolean usePulley) {
    wasmline::Api::warmupEngine(usePulley == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_Wasmline_nativeSupportsAot(JNIEnv *env, jclass thiz) {
    return wasmline::Api::supportsAot() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_crow_wasmline_Wasmline_nativeReleaseEngine(JNIEnv *env, jclass thiz) {
    wasmline::Api::releaseEngine();
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_Wasmline_nativeLoadAot(JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr) {
    return loadPrecompiledModuleCommon(env, keyStr, pathStr, false);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_Wasmline_nativeLoadAotUnsafe(JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr) {
    return loadPrecompiledModuleCommon(env, keyStr, pathStr, true);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_Wasmline_nativeLoadComponent(JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr) {
    const char *key = env->GetStringUTFChars(keyStr, nullptr);
    const char *path = env->GetStringUTFChars(pathStr, nullptr);
    bool success = wasmline::Api::loadComponent(key, path);
    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(pathStr, path);
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_Wasmline_nativeLoadComponentUnsafe(JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr) {
    const char *key = env->GetStringUTFChars(keyStr, nullptr);
    const char *path = env->GetStringUTFChars(pathStr, nullptr);
    bool success = wasmline::Api::loadComponentUnsafe(key, path);
    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(pathStr, path);
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_crow_wasmline_Wasmline_nativeReleaseModule(JNIEnv *env, jclass thiz, jstring keyStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    wasmline::Api::releaseModule(key);
    env->ReleaseStringUTFChars(keyStr, key);
}

JNIEXPORT jbyteArray JNICALL
Java_crow_wasmline_Wasmline_nativeInvokeInbound(JNIEnv *env, jclass thiz, jstring keyStr, jstring actionStr, jbyteArray inputBytes) {
    if (!keyStr || !actionStr || !inputBytes) {
        return transportFailure(env, "JNI inbound invocation received a null input.");
    }

    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* action = env->GetStringUTFChars(actionStr, nullptr);
    jsize actionLen = env->GetStringUTFLength(actionStr);
    if (!key || !action || actionLen < 0) {
        if (action) env->ReleaseStringUTFChars(actionStr, action);
        if (key) env->ReleaseStringUTFChars(keyStr, key);
        return transportFailure(env, "JNI inbound invocation could not read its string input.");
    }

    jsize dataLen = env->GetArrayLength(inputBytes);
    jbyte* dataPtr = dataLen == 0 ? nullptr : env->GetByteArrayElements(inputBytes, nullptr);
    if (dataLen < 0 || (dataLen > 0 && !dataPtr)) {
        if (dataPtr) env->ReleaseByteArrayElements(inputBytes, dataPtr, JNI_ABORT);
        env->ReleaseStringUTFChars(actionStr, action);
        env->ReleaseStringUTFChars(keyStr, key);
        return transportFailure(env, "JNI inbound invocation could not read its byte input.");
    }

    std::string resultData = wasmline::Api::invokeInbound(key, action, (size_t)actionLen, (const char *)dataPtr, (size_t)dataLen);

    if (dataPtr) env->ReleaseByteArrayElements(inputBytes, dataPtr, JNI_ABORT);
    env->ReleaseStringUTFChars(actionStr, action);
    env->ReleaseStringUTFChars(keyStr, key);

    return newByteArray(env, resultData);
}

static jbyteArray invokeTypedCommon(JNIEnv *env, jstring keyStr, jstring exportStr, jbyteArray inputBytes,
                                    wasmline::TypedInvocationKind kind) {
    wasmline::InvocationResult result = wasmline::InvocationResult::failure(
        wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
        "Typed invocation could not be executed.");

    const char* key = keyStr ? env->GetStringUTFChars(keyStr, nullptr) : nullptr;
    const char* exportName = exportStr ? env->GetStringUTFChars(exportStr, nullptr) : nullptr;
    jsize inputLen = 0;
    jbyte* input = nullptr;
    std::string inputData;
    const bool hasValidObjects = keyStr && exportStr && inputBytes && key && exportName;
    if (hasValidObjects) {
        inputLen = env->GetArrayLength(inputBytes);
        input = inputLen > 0 ? env->GetByteArrayElements(inputBytes, nullptr) : nullptr;
        if (inputLen < 0 || (inputLen > 0 && !input)) {
            result = wasmline::InvocationResult::failure(
                wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
                "JNI typed invocation could not read its byte input.");
        } else {
            inputData = inputLen == 0
                            ? std::string()
                            : std::string(reinterpret_cast<const char*>(input), static_cast<size_t>(inputLen));
        }
    } else {
        result = wasmline::InvocationResult::failure(
            wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
            "JNI typed invocation received a null input.");
    }

    if (hasValidObjects && inputLen >= 0 && (inputLen == 0 || input != nullptr)) {
        std::string decodeError;
        if (kind == wasmline::TypedInvocationKind::RAW) {
            std::vector<wasmline::RawValue> arguments;
            if (!wasmline::TypedInvocationCodec::decodeRawArguments(inputData, &arguments, &decodeError)) {
                result = wasmline::InvocationResult::failure(wasmline::WasmlineErrorCode::INVALID_PAYLOAD,
                                                             decodeError.empty() ? "Raw invocation payload is invalid." : decodeError);
            } else {
                result = wasmline::Api::invokeRaw(key, exportName, arguments);
            }
        } else {
            std::vector<wasmline::ComponentValue> arguments;
            if (!wasmline::TypedInvocationCodec::decodeComponentArguments(inputData, &arguments, &decodeError)) {
                result = wasmline::InvocationResult::failure(wasmline::WasmlineErrorCode::INVALID_PAYLOAD,
                                                             decodeError.empty() ? "Component invocation payload is invalid." : decodeError);
            } else {
                result = wasmline::Api::invokeComponent(key, exportName, arguments);
            }
        }
    }

    const std::vector<uint8_t> output = wasmline::TypedInvocationCodec::encodeResult(result, kind);
    if (input) env->ReleaseByteArrayElements(inputBytes, input, JNI_ABORT);
    if (exportName) env->ReleaseStringUTFChars(exportStr, exportName);
    if (key) env->ReleaseStringUTFChars(keyStr, key);

    return newByteArray(env, output);
}

JNIEXPORT jbyteArray JNICALL
Java_crow_wasmline_Wasmline_nativeInvokeRaw(JNIEnv *env, jclass thiz, jstring keyStr, jstring exportStr, jbyteArray inputBytes) {
    return invokeTypedCommon(env, keyStr, exportStr, inputBytes, wasmline::TypedInvocationKind::RAW);
}

JNIEXPORT jbyteArray JNICALL
Java_crow_wasmline_Wasmline_nativeInvokeComponent(JNIEnv *env, jclass thiz, jstring keyStr, jstring exportStr,
                                                   jbyteArray inputBytes) {
    return invokeTypedCommon(env, keyStr, exportStr, inputBytes, wasmline::TypedInvocationKind::COMPONENT);
}

JNIEXPORT void JNICALL
Java_crow_wasmline_Wasmline_nativeSetOutboundHandler(JNIEnv *env, jclass thiz, jstring keyStr, jobject jDispatcher) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    auto handler = std::make_unique<JniHostHandler>(env, jDispatcher);
    wasmline::Api::setOutboundHandler(key, std::move(handler));
    env->ReleaseStringUTFChars(keyStr, key);
}
}
