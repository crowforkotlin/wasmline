#pragma once
#include <jni.h>
#include "WasmHostHandler.h"

class AndroidHostHandler : public WasmHostHandler {
public:
    AndroidHostHandler(JNIEnv* env, jobject dispatcher);
    ~AndroidHostHandler() override;

    std::string invoke(const std::string& action, const std::string& payload) override;

private:
    JavaVM* jvm;
    jobject javaDispatcherRef; // GlobalRef
    jmethodID dispatchMethodId;
};