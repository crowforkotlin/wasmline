#include "AndroidHostHandler.h"
#include "Logger.h"

AndroidHostHandler::AndroidHostHandler(JNIEnv* env, jobject dispatcher) {
    env->GetJavaVM(&jvm);
    // 1. 锁定对象，防止 GC
    javaDispatcherRef = env->NewGlobalRef(dispatcher);

    // 2. 缓存 MethodID
    jclass cls = env->GetObjectClass(dispatcher);
    dispatchMethodId = env->GetMethodID(cls, "dispatch", "(Ljava/lang/String;[B)[B");
    env->DeleteLocalRef(cls);
}

AndroidHostHandler::~AndroidHostHandler() {
    // 析构时释放引用
    JNIEnv* env = nullptr;
    bool attached = false;
    if (jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }

    if (javaDispatcherRef) {
        env->DeleteGlobalRef(javaDispatcherRef);
    }

    if (attached) jvm->DetachCurrentThread();
}

std::string AndroidHostHandler::onOutboundInvoke(const std::string& action, const std::string& payload) {
    JNIEnv* env = nullptr;
    if (jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        // 理论上不会发生，因为 onOutboundInvoke 是在 Session::invokeInbound 线程调用的
        return "";
    }

    jstring jAction = env->NewStringUTF(action.c_str());
    jbyteArray jPayload = env->NewByteArray(payload.size());
    env->SetByteArrayRegion(jPayload, 0, payload.size(), (const jbyte*)payload.data());

    // 调用 Java
    jobject jResult = env->CallObjectMethod(javaDispatcherRef, dispatchMethodId, jAction, jPayload);

    std::string resultStr;
    if (jResult) {
        jbyteArray jResArr = (jbyteArray)jResult;
        jsize len = env->GetArrayLength(jResArr);
        jbyte* body = env->GetByteArrayElements(jResArr, nullptr);
        resultStr.assign((char*)body, len);
        env->ReleaseByteArrayElements(jResArr, body, JNI_ABORT);
    }

    env->DeleteLocalRef(jAction);
    env->DeleteLocalRef(jPayload);
    env->DeleteLocalRef(jResult);

    return resultStr;
}