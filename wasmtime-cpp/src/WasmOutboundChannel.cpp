#include "WasmOutboundHandler.h"

WasmOutboundHandler::WasmOutboundHandler(JNIEnv* env, jobject dispatcher) {
    env->GetJavaVM(&jvm);
    // 1. 创建全局引用，锁定 Java 对象生命周期
    javaDispatcherRef = env->NewGlobalRef(dispatcher);
    
    // 2. 缓存 MethodID: dispatch(String, byte[]): byte[]
    jclass cls = env->GetObjectClass(dispatcher);
    dispatchMethodId = env->GetMethodID(cls, "dispatch", "(Ljava/lang/String;[B)[B");
    env->DeleteLocalRef(cls);
}

WasmOutboundHandler::~WasmOutboundHandler() {
    // 析构时释放全局引用
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

std::string WasmOutboundHandler::call(JNIEnv* env, const std::string& action, const std::string& payload) {
    if (!javaDispatcherRef || !dispatchMethodId) return "";

    // 构造参数
    jstring jAction = env->NewStringUTF(action.c_str());
    jbyteArray jPayload = env->NewByteArray(payload.size());
    env->SetByteArrayRegion(jPayload, 0, payload.size(), (const jbyte*)payload.data());

    // 调用 Java
    jobject jResult = env->CallObjectMethod(javaDispatcherRef, dispatchMethodId, jAction, jPayload);

    // 处理返回值 (深拷贝到 C++ string)
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