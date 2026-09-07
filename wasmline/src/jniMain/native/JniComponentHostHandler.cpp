/**
 * Implements the JNI typed Component Model host handler.
 *
 * Date: 2026-08-07
 * Author: crowforkotlin
 */

#include "JniComponentHostHandler.h"

#include <limits>
#include <string>
#include <utility>

#include "wasmline/invocation/TypedInvocationCodec.h"

namespace {
wasmline::InvocationResult handlerFailure(wasmline::WasmlineErrorCode code,
                                          const char *message) {
  return wasmline::InvocationResult::failure(code, message);
}

bool attachEnvironment(JavaVM *jvm, JNIEnv **environment, bool *attached) {
  if (!jvm || !environment || !attached)
    return false;
  *environment = nullptr;
  *attached = false;
  const jint status =
      jvm->GetEnv(reinterpret_cast<void **>(environment), JNI_VERSION_1_6);
  if (status == JNI_OK && *environment)
    return true;
  if (status != JNI_EDETACHED)
    return false;
#ifdef __ANDROID__
  if (jvm->AttachCurrentThread(environment, nullptr) != JNI_OK)
    return false;
#else
  if (jvm->AttachCurrentThread(reinterpret_cast<void **>(environment),
                               nullptr) != JNI_OK)
    return false;
#endif
  *attached = true;
  return true;
}
} // namespace

JniComponentHostHandler::JniComponentHostHandler(JNIEnv *env,
                                                 jobject dispatcher) {
  if (!env || !dispatcher || env->GetJavaVM(&jvm) != JNI_OK || !jvm)
    return;
  javaDispatcherRef = env->NewGlobalRef(dispatcher);
  if (!javaDispatcherRef)
    return;

  jclass dispatcherClass = env->GetObjectClass(dispatcher);
  if (!dispatcherClass) {
    if (env->ExceptionCheck())
      env->ExceptionClear();
    return;
  }
  dispatchMethodId =
      env->GetMethodID(dispatcherClass, "dispatch",
                       "(Ljava/lang/String;Ljava/lang/String;[B)[B");
  if (!dispatchMethodId && env->ExceptionCheck())
    env->ExceptionClear();
  env->DeleteLocalRef(dispatcherClass);
}

JniComponentHostHandler::~JniComponentHostHandler() {
  if (!jvm || !javaDispatcherRef)
    return;
  JNIEnv *env = nullptr;
  bool attached = false;
  if (!attachEnvironment(jvm, &env, &attached))
    return;
  env->DeleteGlobalRef(javaDispatcherRef);
  if (attached)
    jvm->DetachCurrentThread();
}

bool JniComponentHostHandler::isValid() const noexcept {
  return jvm != nullptr && javaDispatcherRef != nullptr &&
         dispatchMethodId != nullptr;
}

wasmline::InvocationResult JniComponentHostHandler::onComponentHostInvoke(
    std::string_view interfaceName, std::string_view functionName,
    const std::vector<wasmline::ComponentValue> &arguments) {
  if (!isValid()) {
    return handlerFailure(
        wasmline::WasmlineErrorCode::HANDLER_FAILED,
        "Wasmline Java typed Component host dispatcher is not initialized.");
  }

  const auto encodedArguments =
      wasmline::TypedInvocationCodec::encodeComponentArguments(arguments);
  if (encodedArguments.empty()) {
    return handlerFailure(
        wasmline::WasmlineErrorCode::INVALID_PAYLOAD,
        "Typed Component host arguments cannot be encoded for JNI.");
  }
  if (interfaceName.size() >
          static_cast<size_t>(std::numeric_limits<jsize>::max()) ||
      functionName.size() >
          static_cast<size_t>(std::numeric_limits<jsize>::max()) ||
      encodedArguments.size() >
          static_cast<size_t>(std::numeric_limits<jsize>::max())) {
    return handlerFailure(wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
                          "Typed Component host JNI payload is too large.");
  }

  JNIEnv *env = nullptr;
  bool attached = false;
  if (!attachEnvironment(jvm, &env, &attached)) {
    return handlerFailure(wasmline::WasmlineErrorCode::HANDLER_FAILED,
                          "Wasmline could not obtain a JNI environment.");
  }
  const auto detach = [&]() {
    if (attached)
      jvm->DetachCurrentThread();
  };

  const std::string interfaceText(interfaceName);
  const std::string functionText(functionName);
  jstring javaInterface = env->NewStringUTF(interfaceText.c_str());
  jstring javaFunction = env->NewStringUTF(functionText.c_str());
  jbyteArray javaArguments =
      env->NewByteArray(static_cast<jsize>(encodedArguments.size()));
  if (!javaInterface || !javaFunction || !javaArguments) {
    if (javaInterface)
      env->DeleteLocalRef(javaInterface);
    if (javaFunction)
      env->DeleteLocalRef(javaFunction);
    if (javaArguments)
      env->DeleteLocalRef(javaArguments);
    if (env->ExceptionCheck())
      env->ExceptionClear();
    detach();
    return handlerFailure(
        wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
        "Wasmline could not allocate typed Component JNI arguments.");
  }
  env->SetByteArrayRegion(
      javaArguments, 0, static_cast<jsize>(encodedArguments.size()),
      reinterpret_cast<const jbyte *>(encodedArguments.data()));
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    env->DeleteLocalRef(javaInterface);
    env->DeleteLocalRef(javaFunction);
    env->DeleteLocalRef(javaArguments);
    detach();
    return handlerFailure(
        wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
        "Wasmline could not copy typed Component JNI arguments.");
  }

  jobject javaResult =
      env->CallObjectMethod(javaDispatcherRef, dispatchMethodId, javaInterface,
                            javaFunction, javaArguments);
  env->DeleteLocalRef(javaInterface);
  env->DeleteLocalRef(javaFunction);
  env->DeleteLocalRef(javaArguments);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    detach();
    return handlerFailure(wasmline::WasmlineErrorCode::HANDLER_FAILED,
                          "Wasmline typed Component host adapter failed.");
  }
  if (!javaResult) {
    detach();
    return handlerFailure(wasmline::WasmlineErrorCode::ACTION_NOT_BOUND,
                          "No typed Component host adapter is bound.");
  }

  jbyteArray javaResponse = static_cast<jbyteArray>(javaResult);
  const jsize responseSize = env->GetArrayLength(javaResponse);
  if (responseSize < 0 || env->ExceptionCheck()) {
    if (env->ExceptionCheck())
      env->ExceptionClear();
    env->DeleteLocalRef(javaResult);
    detach();
    return handlerFailure(
        wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
        "Wasmline could not read the typed Component JNI response.");
  }
  std::string response(static_cast<size_t>(responseSize), '\0');
  if (responseSize > 0) {
    env->GetByteArrayRegion(javaResponse, 0, responseSize,
                            reinterpret_cast<jbyte *>(response.data()));
  }
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    env->DeleteLocalRef(javaResult);
    detach();
    return handlerFailure(
        wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
        "Wasmline could not copy the typed Component JNI response.");
  }
  env->DeleteLocalRef(javaResult);
  detach();

  wasmline::InvocationResult result =
      handlerFailure(wasmline::WasmlineErrorCode::RESPONSE_MALFORMED,
                     "Typed Component host response is malformed.");
  std::string decodeError;
  if (!wasmline::TypedInvocationCodec::decodeComponentResult(response, &result,
                                                             &decodeError)) {
    return wasmline::InvocationResult::failure(
        wasmline::WasmlineErrorCode::RESPONSE_MALFORMED,
        decodeError.empty() ? "Typed Component host response is malformed."
                            : decodeError);
  }
  return result;
}
