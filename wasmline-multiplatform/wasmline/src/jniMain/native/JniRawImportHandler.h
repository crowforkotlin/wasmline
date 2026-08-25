/**
 * Defines the JNI synchronous Core Wasm raw-import adapter.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */

#pragma once

#include <jni.h>

#include "../../nativeMain/native/WasmlineNative.h"

/**
 * Forwards one Core Wasm raw import to a JVM dispatcher.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
class JniRawImportHandler {
public:
    /** Creates a handler for a JVM raw-import dispatcher. */
    JniRawImportHandler(JNIEnv* env, jobject dispatcher);

    /** Releases the JVM global reference. */
    ~JniRawImportHandler();

    /** Returns whether the dispatcher method was resolved. */
    bool isValid() const noexcept;

    /** Invokes the Java dispatcher and returns an encoded raw result. */
    char* invoke(const char* sessionKey, const char* module, size_t moduleLen, const char* name, size_t nameLen,
                 const void* arguments, size_t argumentsLen, size_t* outLen);

    /** Callback trampoline passed to the native Core session. */
    static char* callback(void* user, const char* sessionKey, const char* module, size_t moduleLen, const char* name,
                          size_t nameLen, const void* arguments, size_t argumentsLen, size_t* outLen);

    /** Callback user finalizer passed to the native Core session. */
    static void finalize(void* user);

    /** Releases a callback response buffer. */
    static void freeBuffer(char* buffer);

private:
    JavaVM* jvm_ = nullptr;
    jobject dispatcher_ = nullptr;
    jmethodID method_ = nullptr;
};
