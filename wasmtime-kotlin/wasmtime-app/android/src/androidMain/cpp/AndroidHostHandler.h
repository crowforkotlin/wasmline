#pragma once
#include <jni.h>
#include "WasmOutboundHandler.h"

class AndroidHostHandler : public WasmOutboundHandler {
public:
    AndroidHostHandler(JNIEnv* env, jobject dispatcher);
    ~AndroidHostHandler() override;

    std::string onOutboundInvoke(const std::string& action, const std::string& payload) override;

private:
    JavaVM* jvm;
    jobject javaDispatcherRef; // GlobalRef
    jmethodID dispatchMethodId;
};