#pragma once
#include "jni.h"
#include "OutboundHandler.h"

class JniHostHandler : public wasmline::OutboundHandler {
public:
    JniHostHandler(JNIEnv* env, jobject dispatcher);
    ~JniHostHandler() override;

//    std::string onOutboundInvoke(const std::string& action, const std::string& payload) override;
    std::string onOutboundInvoke(std::string_view action, std::string_view payload) override;

private:
    JavaVM* jvm;
    jobject javaDispatcherRef; // GlobalRef
    jmethodID dispatchMethodId;
};