/**
 * Implements the JNI outbound handler.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "JniHostHandler.h"
#include "wasmline/protocol/WasmlineProtocol.h"
#include "logging/NativeLogger.h"

/** Creates a handler and caches its Java dispatch method. */
JniHostHandler::JniHostHandler(JNIEnv *env, jobject dispatcher) {
    env->GetJavaVM(&jvm);
    javaDispatcherRef = env->NewGlobalRef(dispatcher);

    jclass cls = env->GetObjectClass(dispatcher);
    dispatchMethodId = env->GetMethodID(cls, "dispatch", "(Ljava/lang/String;[B)[B");
    env->DeleteLocalRef(cls);
}

/** Releases the Java reference and detaches an attached thread. */
JniHostHandler::~JniHostHandler() {
    JNIEnv *env = nullptr;
    bool attached = false;
    if (jvm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
#ifdef __ANDROID__
        jvm->AttachCurrentThread(&env, nullptr);
#else
        jvm->AttachCurrentThread((void**)&env, nullptr);
#endif
        attached = true;
    }

    if (javaDispatcherRef) {
        env->DeleteGlobalRef(javaDispatcherRef);
    }

    if (attached) jvm->DetachCurrentThread();
}

/** Dispatches the outbound call and returns the encoded host result. */
std::string JniHostHandler::onOutboundInvoke(const std::string_view action, const std::string_view payload) {
    JNIEnv *env = nullptr;
    bool attached = false;

    if (jvm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
#ifdef __ANDROID__
        if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return "";
#else
        if (jvm->AttachCurrentThread((void**)&env, nullptr) != JNI_OK) return "";
#endif
        attached = true;
    }

    const std::string actionText(action);
    jstring jAction = env->NewStringUTF(actionText.c_str());
    jbyteArray jPayload = env->NewByteArray((jsize)payload.size());
    if (payload.size() > 0) {
        env->SetByteArrayRegion(jPayload, 0, (jsize)payload.size(), (const jbyte *) payload.data());
    }

    jobject jResult = env->CallObjectMethod(javaDispatcherRef, dispatchMethodId, jAction, jPayload);

    env->DeleteLocalRef(jAction);
    env->DeleteLocalRef(jPayload);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        if (attached) jvm->DetachCurrentThread();
        return wasmline::WasmlineResponseCodec::failure(
            wasmline::WasmlineErrorCode::HANDLER_FAILED,
            "Wasmline outbound action handler failed.");
    }

    if (!jResult) {
        if (attached) jvm->DetachCurrentThread();
        return wasmline::WasmlineResponseCodec::failure(
            wasmline::WasmlineErrorCode::ACTION_NOT_BOUND,
            "No Wasmline outbound action is bound.");
    }

    std::string resultStr;
    if (jResult) {
        jbyteArray jResArr = (jbyteArray) jResult;
        jsize len = env->GetArrayLength(jResArr);
        if (len > 0) {
            resultStr.resize(len);
            env->GetByteArrayRegion(jResArr, 0, len, (jbyte*)resultStr.data());
        }
        env->DeleteLocalRef(jResult);
    }

    if (attached) jvm->DetachCurrentThread();

    return resultStr;
}
