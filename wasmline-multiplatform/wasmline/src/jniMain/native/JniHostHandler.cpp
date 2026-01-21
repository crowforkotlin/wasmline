#include "JniHostHandler.h"
#include "Logger.h"

JniHostHandler::JniHostHandler(JNIEnv *env, jobject dispatcher) {
    env->GetJavaVM(&jvm);
    // 1. lock objects to prevent gc
    javaDispatcherRef = env->NewGlobalRef(dispatcher);

    // 2. cache method
    jclass cls = env->GetObjectClass(dispatcher);
    dispatchMethodId = env->GetMethodID(cls, "dispatch", "(Ljava/lang/String;[B)[B");
    env->DeleteLocalRef(cls);
}

JniHostHandler::~JniHostHandler() {
    // release references on destruction
    JNIEnv *env = nullptr;
    bool attached = false;
    if (jvm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
#ifdef __ANDROID__
        // Android NDK requires that JNIEnv be passed in directly**
        jvm->AttachCurrentThread(&env, nullptr);
#else
        // desktop requires void to be passed in**
        jvm->AttachCurrentThread((void**)&env, nullptr);
#endif
        attached = true;
    }

    if (javaDispatcherRef) {
        env->DeleteGlobalRef(javaDispatcherRef);
    }

    if (attached) jvm->DetachCurrentThread();
}

std::string JniHostHandler::onOutboundInvoke(const std::string_view action, const std::string_view payload) {
    JNIEnv *env = nullptr;
    bool attached = false;

    // Robustness check: Although InvokeInbound is theoretically synchronous,
    // But if thread support is enabled internally in Wasm, crash prevention must be done here.
    if (jvm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
#ifdef __ANDROID__
        if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return "";
#else
        if (jvm->AttachCurrentThread((void**)&env, nullptr) != JNI_OK) return "";
#endif
        attached = true;
    }

    jstring jAction = env->NewStringUTF(action.data());
    jbyteArray jPayload = env->NewByteArray((jsize)payload.size());
    if (payload.size() > 0) {
        env->SetByteArrayRegion(jPayload, 0, (jsize)payload.size(), (const jbyte *) payload.data());
    }

    // call java
    jobject jResult = env->CallObjectMethod(javaDispatcherRef, dispatchMethodId, jAction, jPayload);

    env->DeleteLocalRef(jAction);
    env->DeleteLocalRef(jPayload);

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