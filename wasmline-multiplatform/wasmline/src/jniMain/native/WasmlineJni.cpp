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
#include "wasmline/runtime/AotLoadPathDiagnostics.h"
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

static jboolean loadComponentCommon(JNIEnv *env, jstring keyStr, jstring pathStr, bool unsafe,
                                    const wasmline::WasmlineArtifactFormat* artifactFormat = nullptr) {
    if (!artifactFormat) {
        LOGE("[Wasmline] JNI --> Native artifact loading requires an explicit format.");
        return JNI_FALSE;
    }
    if (!env || !keyStr || !pathStr) return JNI_FALSE;
    const char *key = env->GetStringUTFChars(keyStr, nullptr);
    const char *path = env->GetStringUTFChars(pathStr, nullptr);
    if (!key || !path) {
        if (path) env->ReleaseStringUTFChars(pathStr, path);
        if (key) env->ReleaseStringUTFChars(keyStr, key);
        return JNI_FALSE;
    }
    bool success = unsafe ? wasmline::Api::loadComponentUnsafe(key, path, *artifactFormat)
                          : wasmline::Api::loadComponent(key, path, *artifactFormat);
    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(pathStr, path);
    return success ? JNI_TRUE : JNI_FALSE;
}

static jboolean loadComponentWithFormatCommon(JNIEnv *env, jstring keyStr, jstring pathStr, jint formatCode, bool unsafe) {
    wasmline::WasmlineArtifactFormat artifactFormat;
    if (!wasmline::Api::tryArtifactFormatFromCode(static_cast<int32_t>(formatCode), &artifactFormat)) {
        LOGE("[Wasmline] JNI --> Invalid native artifact format code: %d", static_cast<int>(formatCode));
        return JNI_FALSE;
    }
    return loadComponentCommon(env, keyStr, pathStr, unsafe, &artifactFormat);
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

JNIEXPORT jstring JNICALL
Java_crow_wasmline_Wasmline_nativeWasmtimeVersion(JNIEnv *env, jclass thiz) {
    return env ? env->NewStringUTF(wasmline::Api::wasmtimeVersion()) : nullptr;
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_Wasmline_nativeSupportsCranelift(JNIEnv *env, jclass thiz) {
    return wasmline::Api::supportsCranelift() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_Wasmline_nativeSupportsPulley(JNIEnv *env, jclass thiz) {
    return wasmline::Api::supportsPulley() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_crow_wasmline_Wasmline_nativeReleaseEngine(JNIEnv *env, jclass thiz) {
    wasmline::Api::releaseEngine();
}

JNIEXPORT void JNICALL
Java_crow_wasmline_Wasmline_nativeResetAotLoadPathDiagnostics(JNIEnv *env, jclass thiz) {
    wasmline::AotLoadPathDiagnostics::reset();
}

JNIEXPORT jlong JNICALL
Java_crow_wasmline_Wasmline_nativeAotLoadPathDiagnostics(JNIEnv *env, jclass thiz) {
    return static_cast<jlong>(wasmline::AotLoadPathDiagnostics::snapshot());
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
Java_crow_wasmline_Wasmline_nativeLoadAotWithFormat(JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr, jint formatCode) {
    return loadPrecompiledModuleWithFormatCommon(env, keyStr, pathStr, formatCode, false);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_Wasmline_nativeLoadAotUnsafeWithFormat(JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr,
                                                          jint formatCode) {
    return loadPrecompiledModuleWithFormatCommon(env, keyStr, pathStr, formatCode, true);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_Wasmline_nativeLoadComponent(JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr) {
    return loadComponentCommon(env, keyStr, pathStr, false);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_Wasmline_nativeLoadComponentUnsafe(JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr) {
    return loadComponentCommon(env, keyStr, pathStr, true);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_Wasmline_nativeLoadComponentWithFormat(JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr,
                                                          jint formatCode) {
    return loadComponentWithFormatCommon(env, keyStr, pathStr, formatCode, false);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_Wasmline_nativeLoadComponentUnsafeWithFormat(JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr,
                                                                jint formatCode) {
    return loadComponentWithFormatCommon(env, keyStr, pathStr, formatCode, true);
}

JNIEXPORT void JNICALL
Java_crow_wasmline_Wasmline_nativeReleaseModule(JNIEnv *env, jclass thiz, jstring keyStr) {
    if (!env || !keyStr) return;
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    if (!key) return;
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
                                    wasmline::TypedInvocationKind kind, bool componentInstance = false) {
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
                result = componentInstance
                             ? wasmline::Api::invokeComponentInstance(key, exportName, arguments)
                             : wasmline::Api::invokeComponent(key, exportName, arguments);
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

JNIEXPORT jbyteArray JNICALL
Java_crow_wasmline_Wasmline_nativeInvokeComponentInstance(JNIEnv *env, jclass thiz, jstring instanceKeyStr,
                                                           jstring exportStr, jbyteArray inputBytes) {
    return invokeTypedCommon(env, instanceKeyStr, exportStr, inputBytes, wasmline::TypedInvocationKind::COMPONENT, true);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_Wasmline_nativeInstantiateComponent(JNIEnv* env, jclass thiz, jstring artifactKeyStr,
                                                        jstring instanceKeyStr, jobject jDispatcher) {
    if (!env || !artifactKeyStr || !instanceKeyStr || !jDispatcher) return JNI_FALSE;
    const char* artifactKey = env->GetStringUTFChars(artifactKeyStr, nullptr);
    const char* instanceKey = env->GetStringUTFChars(instanceKeyStr, nullptr);
    if (!artifactKey || !instanceKey) {
        if (instanceKey) env->ReleaseStringUTFChars(instanceKeyStr, instanceKey);
        if (artifactKey) env->ReleaseStringUTFChars(artifactKeyStr, artifactKey);
        return JNI_FALSE;
    }

    auto handler = std::make_unique<JniComponentHostHandler>(env, jDispatcher);
    const bool instantiated = handler->isValid() &&
                              wasmline::Api::instantiateComponent(artifactKey, instanceKey, std::move(handler));
    env->ReleaseStringUTFChars(instanceKeyStr, instanceKey);
    env->ReleaseStringUTFChars(artifactKeyStr, artifactKey);
    return instantiated ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_crow_wasmline_Wasmline_nativeReleaseComponentInstance(JNIEnv* env, jclass thiz, jstring instanceKeyStr) {
    if (!env || !instanceKeyStr) return;
    const char* instanceKey = env->GetStringUTFChars(instanceKeyStr, nullptr);
    if (!instanceKey) return;
    wasmline::Api::releaseComponentInstance(instanceKey);
    env->ReleaseStringUTFChars(instanceKeyStr, instanceKey);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_Wasmline_nativeDropComponentResource(JNIEnv* env, jclass thiz, jstring instanceKeyStr, jbyteArray resourceBytes) {
    if (!env || !instanceKeyStr || !resourceBytes) return JNI_FALSE;
    const char* instanceKey = env->GetStringUTFChars(instanceKeyStr, nullptr);
    if (!instanceKey) return JNI_FALSE;
    const jsize length = env->GetArrayLength(resourceBytes);
    jbyte* bytes = env->GetByteArrayElements(resourceBytes, nullptr);
    if (length > 0 && !bytes) {
        env->ReleaseStringUTFChars(instanceKeyStr, instanceKey);
        return JNI_FALSE;
    }
    const auto* input = reinterpret_cast<const uint8_t*>(bytes);
    std::vector<wasmline::ComponentValue> values;
    std::string error;
    const bool decoded = wasmline::TypedInvocationCodec::decodeComponentArguments(
        std::string_view(reinterpret_cast<const char*>(input), static_cast<size_t>(length)), &values, &error);
    const bool dropped = decoded && values.size() == 1 && values.front().kind() == wasmline::ComponentValue::Kind::RESOURCE &&
                         wasmline::Api::dropComponentResource(instanceKey, values.front().resourceValue());
    if (bytes) env->ReleaseByteArrayElements(resourceBytes, bytes, JNI_ABORT);
    env->ReleaseStringUTFChars(instanceKeyStr, instanceKey);
    return dropped ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_crow_wasmline_Wasmline_nativeCreateComponentHostResource(JNIEnv* env, jclass thiz, jstring instanceKeyStr,
                                                               jstring interfaceIdStr, jstring resourceNameStr,
                                                               jint representation) {
    if (!env || !instanceKeyStr || !interfaceIdStr || !resourceNameStr || representation == 0) return nullptr;
    const char* instanceKey = env->GetStringUTFChars(instanceKeyStr, nullptr);
    const char* interfaceId = env->GetStringUTFChars(interfaceIdStr, nullptr);
    const char* resourceName = env->GetStringUTFChars(resourceNameStr, nullptr);
    if (!instanceKey || !interfaceId || !resourceName) {
        if (resourceName) env->ReleaseStringUTFChars(resourceNameStr, resourceName);
        if (interfaceId) env->ReleaseStringUTFChars(interfaceIdStr, interfaceId);
        if (instanceKey) env->ReleaseStringUTFChars(instanceKeyStr, instanceKey);
        return nullptr;
    }
    wasmline::ComponentResourceReference reference;
    const bool created = wasmline::Api::createComponentHostResource(
        instanceKey, interfaceId, resourceName, static_cast<uint32_t>(representation), &reference);
    env->ReleaseStringUTFChars(resourceNameStr, resourceName);
    env->ReleaseStringUTFChars(interfaceIdStr, interfaceId);
    env->ReleaseStringUTFChars(instanceKeyStr, instanceKey);
    if (!created) return nullptr;
    return newByteArray(env, wasmline::TypedInvocationCodec::encodeComponentArguments(
                                 {wasmline::ComponentValue::resource(std::move(reference))}));
}

JNIEXPORT void JNICALL
Java_crow_wasmline_Wasmline_nativeSetOutboundHandler(JNIEnv *env, jclass thiz, jstring keyStr, jstring codecStr,
                                                      jobject jDispatcher) {
    if (!keyStr || !codecStr || !jDispatcher) return;
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* codec = env->GetStringUTFChars(codecStr, nullptr);
    if (!key || !codec) {
        if (codec) env->ReleaseStringUTFChars(codecStr, codec);
        if (key) env->ReleaseStringUTFChars(keyStr, key);
        return;
    }
    auto handler = std::make_unique<JniHostHandler>(env, jDispatcher);
    if (!handler->isValid()) {
        env->ReleaseStringUTFChars(codecStr, codec);
        env->ReleaseStringUTFChars(keyStr, key);
        return;
    }
    wasmline::Api::setOutboundHandler(key, codec, std::move(handler));
    env->ReleaseStringUTFChars(codecStr, codec);
    env->ReleaseStringUTFChars(keyStr, key);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_Wasmline_nativeSetComponentHostHandler(JNIEnv* env, jclass thiz, jstring keyStr, jobject jDispatcher) {
    if (!env || !keyStr || !jDispatcher) return JNI_FALSE;
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    if (!key) return JNI_FALSE;

    auto handler = std::make_unique<JniComponentHostHandler>(env, jDispatcher);
    if (!handler->isValid()) {
        env->ReleaseStringUTFChars(keyStr, key);
        return JNI_FALSE;
    }
    const bool installed = wasmline::Api::setComponentHostHandler(key, std::move(handler));
    env->ReleaseStringUTFChars(keyStr, key);
    return installed ? JNI_TRUE : JNI_FALSE;
}
}
