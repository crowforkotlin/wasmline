#pragma once
#include <jni.h>
#include <string>

class WasmOutboundChannel {
public:
    WasmOutboundChannel(JNIEnv* env, jobject dispatcher);
    ~WasmOutboundChannel();

    // 调用 Java 分发器
    std::string call(JNIEnv* env, const std::string& action, const std::string& payload);

private:
    JavaVM* jvm;
    jobject javaDispatcherRef; // 全局引用
    jmethodID dispatchMethodId;
};