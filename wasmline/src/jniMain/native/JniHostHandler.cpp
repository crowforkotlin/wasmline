/**
 * Implements the JNI outbound handler.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "JniHostHandler.h"
#include <limits>
#include "wasmline/protocol/WasmlineProtocol.h"
#include "wasmline/internal/logging/NativeLogger.h"

/** Creates a handler and caches its Java dispatch method. */
JniHostHandler::JniHostHandler(JNIEnv *env, jobject dispatcher) {
    if (!env || !dispatcher || env->GetJavaVM(&jvm) != JNI_OK || !jvm) return;
    javaDispatcherRef = env->NewGlobalRef(dispatcher);
    if (!javaDispatcherRef) return;

    jclass cls = env->GetObjectClass(dispatcher);
    if (!cls) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    dispatchMethodId = env->GetMethodID(cls, "dispatch", "(Ljava/lang/String;[B)[B");
    if (!dispatchMethodId && env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(cls);
}

bool JniHostHandler::isValid() const noexcept {
    return jvm != nullptr && javaDispatcherRef != nullptr && dispatchMethodId != nullptr;
}

/** Releases the Java reference and detaches an attached thread. */
JniHostHandler::~JniHostHandler() {
    if (!jvm) return;
    JNIEnv *env = nullptr;
    bool attached = false;
    const jint envStatus = jvm->GetEnv((void **) &env, JNI_VERSION_1_6);
    if (envStatus == JNI_EDETACHED) {
#ifdef __ANDROID__
        if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
#else
        if (jvm->AttachCurrentThread((void**)&env, nullptr) != JNI_OK) return;
#endif
        attached = true;
    } else if (envStatus != JNI_OK || !env) {
        return;
    }

    if (javaDispatcherRef) {
        env->DeleteGlobalRef(javaDispatcherRef);
    }

    if (attached) jvm->DetachCurrentThread();
}

/** Dispatches the outbound call and returns the encoded host result. */
std::string JniHostHandler::onOutboundInvoke(const std::string_view action, const std::string_view payload) {
    if (!isValid()) {
        return wasmline::WasmlineResponseCodec::failure(
            wasmline::WasmlineErrorCode::HANDLER_FAILED,
            "Wasmline Java outbound dispatcher is not initialized.");
    }

    JNIEnv *env = nullptr;
    bool attached = false;

    const jint envStatus = jvm->GetEnv((void **) &env, JNI_VERSION_1_6);
    if (envStatus == JNI_EDETACHED) {
#ifdef __ANDROID__
        if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return "";
#else
        if (jvm->AttachCurrentThread((void**)&env, nullptr) != JNI_OK) return "";
#endif
        attached = true;
    } else if (envStatus != JNI_OK || !env) {
        return wasmline::WasmlineResponseCodec::failure(
            wasmline::WasmlineErrorCode::HANDLER_FAILED,
            "Wasmline could not obtain a JNI environment.");
    }

    auto detach = [&]() {
        if (attached) jvm->DetachCurrentThread();
    };

    if (action.size() > static_cast<size_t>(std::numeric_limits<jsize>::max()) ||
        payload.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        detach();
        return wasmline::WasmlineResponseCodec::failure(
            wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
            "Wasmline outbound JNI payload is too large.");
    }

    const std::string actionText(action);
    jstring jAction = env->NewStringUTF(actionText.c_str());
    jbyteArray jPayload = env->NewByteArray((jsize)payload.size());
    if (!jAction || !jPayload) {
        if (jAction) env->DeleteLocalRef(jAction);
        if (jPayload) env->DeleteLocalRef(jPayload);
        if (env->ExceptionCheck()) env->ExceptionClear();
        detach();
        return wasmline::WasmlineResponseCodec::failure(
            wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
            "Wasmline could not allocate JNI outbound arguments.");
    }
    if (payload.size() > 0) {
        env->SetByteArrayRegion(jPayload, 0, (jsize)payload.size(), (const jbyte *) payload.data());
    }

    jobject jResult = env->CallObjectMethod(javaDispatcherRef, dispatchMethodId, jAction, jPayload);

    env->DeleteLocalRef(jAction);
    env->DeleteLocalRef(jPayload);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        detach();
        return wasmline::WasmlineResponseCodec::failure(
            wasmline::WasmlineErrorCode::HANDLER_FAILED,
            "Wasmline outbound action handler failed.");
    }

    if (!jResult) {
        detach();
        return wasmline::WasmlineResponseCodec::failure(
            wasmline::WasmlineErrorCode::ACTION_NOT_BOUND,
            "No Wasmline outbound action is bound.");
    }

    std::string resultStr;
    if (jResult) {
        jbyteArray jResArr = (jbyteArray) jResult;
        jsize len = env->GetArrayLength(jResArr);
        if (len < 0 || env->ExceptionCheck()) {
            env->ExceptionClear();
            env->DeleteLocalRef(jResult);
            detach();
            return wasmline::WasmlineResponseCodec::failure(
                wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
                "Wasmline could not read the JNI outbound response.");
        }
        if (len > 0) {
            resultStr.resize(len);
            env->GetByteArrayRegion(jResArr, 0, len, (jbyte*)resultStr.data());
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                env->DeleteLocalRef(jResult);
                detach();
                return wasmline::WasmlineResponseCodec::failure(
                    wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
                    "Wasmline could not copy the JNI outbound response.");
            }
        }
        env->DeleteLocalRef(jResult);
    }

    detach();

    return resultStr;
}
