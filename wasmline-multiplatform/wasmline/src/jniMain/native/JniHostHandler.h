/**
 * Defines the JNI outbound handler.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#pragma once
#include "jni.h"
#include "wasmline/runtime/OutboundHandler.h"

/** Forwards outbound calls from Wasm to a Java dispatcher. */
class JniHostHandler : public wasmline::OutboundHandler {
public:
    /** Creates a handler for the Java dispatcher. */
    JniHostHandler(JNIEnv* env, jobject dispatcher);

    /** Releases JNI references owned by the handler. */
    ~JniHostHandler() override;

    /** Dispatches an outbound call to Java. */
    std::string onOutboundInvoke(std::string_view action, std::string_view payload) override;

private:
    JavaVM* jvm;
    jobject javaDispatcherRef;
    jmethodID dispatchMethodId;
};
