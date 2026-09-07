/**
 * Implements the JNI synchronous Core Wasm raw-import adapter.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */

#include "JniRawImportHandler.h"

#include <cstdlib>
#include <cstring>
#include <limits>
#include <string>
#include <vector>

#include "wasmline/invocation/TypedInvocationCodec.h"
#include "wasmline/protocol/WasmlineProtocol.h"

namespace {
    bool attach(JavaVM* jvm, JNIEnv** env, bool* attached) {
        if (!jvm || !env || !attached) return false;
        *env = nullptr;
        *attached = false;
        const jint status = jvm->GetEnv(reinterpret_cast<void**>(env), JNI_VERSION_1_6);
        if (status == JNI_OK && *env) return true;
        if (status != JNI_EDETACHED) return false;
#ifdef __ANDROID__
        if (jvm->AttachCurrentThread(env, nullptr) != JNI_OK) return false;
#else
        if (jvm->AttachCurrentThread(reinterpret_cast<void**>(env), nullptr) != JNI_OK) return false;
#endif
        *attached = true;
        return true;
    }

    char* copyResult(const std::vector<uint8_t>& bytes, size_t* outLen) {
        if (outLen) *outLen = 0;
        if (bytes.empty()) return nullptr;
        char* result = static_cast<char*>(std::malloc(bytes.size()));
        if (!result) return nullptr;
        std::memcpy(result, bytes.data(), bytes.size());
        if (outLen) *outLen = bytes.size();
        return result;
    }
}

JniRawImportHandler::JniRawImportHandler(JNIEnv* env, jobject dispatcher) {
    if (!env || !dispatcher || env->GetJavaVM(&jvm_) != JNI_OK || !jvm_) return;
    dispatcher_ = env->NewGlobalRef(dispatcher);
    if (!dispatcher_) return;
    jclass cls = env->GetObjectClass(dispatcher);
    if (!cls) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    method_ = env->GetMethodID(cls, "dispatchRaw", "(Ljava/lang/String;Ljava/lang/String;[B)[B");
    if (!method_ && env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(cls);
}

JniRawImportHandler::~JniRawImportHandler() {
    if (!jvm_ || !dispatcher_) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (!attach(jvm_, &env, &attached)) return;
    env->DeleteGlobalRef(dispatcher_);
    dispatcher_ = nullptr;
    if (attached) jvm_->DetachCurrentThread();
}

bool JniRawImportHandler::isValid() const noexcept {
    return jvm_ != nullptr && dispatcher_ != nullptr && method_ != nullptr;
}

char* JniRawImportHandler::invoke(const char* sessionKey, const char* module, size_t moduleLen, const char* name,
                                  size_t nameLen, const void* arguments, size_t argumentsLen, size_t* outLen) {
    if (outLen) *outLen = 0;
    if (!isValid() || !sessionKey || !module || !name || (argumentsLen > 0 && !arguments)) return nullptr;
    if (moduleLen > static_cast<size_t>(std::numeric_limits<jsize>::max()) ||
        nameLen > static_cast<size_t>(std::numeric_limits<jsize>::max()) ||
        argumentsLen > static_cast<size_t>(std::numeric_limits<jsize>::max())) return nullptr;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (!attach(jvm_, &env, &attached)) return nullptr;
    const auto detach = [&]() {
        if (attached) jvm_->DetachCurrentThread();
    };
    const std::string moduleText(module, moduleLen);
    const std::string nameText(name, nameLen);
    jstring jModule = env->NewStringUTF(moduleText.c_str());
    jstring jName = env->NewStringUTF(nameText.c_str());
    jbyteArray jArguments = env->NewByteArray(static_cast<jsize>(argumentsLen));
    if (!jModule || !jName || !jArguments) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (jModule) env->DeleteLocalRef(jModule);
        if (jName) env->DeleteLocalRef(jName);
        if (jArguments) env->DeleteLocalRef(jArguments);
        detach();
        return nullptr;
    }
    if (argumentsLen > 0) env->SetByteArrayRegion(jArguments, 0, static_cast<jsize>(argumentsLen),
                                                   static_cast<const jbyte*>(arguments));
    jobject result = env->CallObjectMethod(dispatcher_, method_, jModule, jName, jArguments);
    env->DeleteLocalRef(jModule);
    env->DeleteLocalRef(jName);
    env->DeleteLocalRef(jArguments);
    if (env->ExceptionCheck() || !result) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (result) env->DeleteLocalRef(result);
        detach();
        return nullptr;
    }
    jbyteArray array = static_cast<jbyteArray>(result);
    const jsize length = env->GetArrayLength(array);
    if (length < 0 || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(result);
        detach();
        return nullptr;
    }
    std::vector<uint8_t> bytes(static_cast<size_t>(length));
    if (length > 0) env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    const bool failed = env->ExceptionCheck();
    if (failed) env->ExceptionClear();
    env->DeleteLocalRef(result);
    detach();
    return failed ? nullptr : copyResult(bytes, outLen);
}

char* JniRawImportHandler::callback(void* user, const char* sessionKey, const char* module, size_t moduleLen,
                                    const char* name, size_t nameLen, const void* arguments, size_t argumentsLen,
                                    size_t* outLen) {
    auto* handler = static_cast<JniRawImportHandler*>(user);
    return handler ? handler->invoke(sessionKey, module, moduleLen, name, nameLen, arguments, argumentsLen, outLen) : nullptr;
}

void JniRawImportHandler::finalize(void* user) {
    delete static_cast<JniRawImportHandler*>(user);
}

void JniRawImportHandler::freeBuffer(char* buffer) {
    std::free(buffer);
}
