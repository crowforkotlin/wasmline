/**
 * Provides the JNI bridge for the Wasmline native API.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

/**
 * Provides the floating-point initialization symbol required by the Wasmtime
 * static library on non-MSVC Windows targets.
 */
#if defined(_WIN32) || defined(_WIN64)
extern "C" {
int _fltused = 0;
}
#endif

#include "WasmlineJni.h"
#include "../../nativeMain/native/WasmlineNative.h"
#include "JniRawImportHandler.h"
#include "wasmline/internal/logging/NativeLogger.h"
#include "wasmline/invocation/TypedInvocationCodec.h"
#include "wasmline/protocol/WasmlineProtocol.h"
#include "wasmline/runtime/AotLoadPathDiagnostics.h"
#include <limits>
#include <vector>

static jbyteArray newByteArray(JNIEnv *env, const void *data, size_t size) {
  if (size > static_cast<size_t>(std::numeric_limits<jsize>::max()))
    return nullptr;
  jbyteArray array = env->NewByteArray(static_cast<jsize>(size));
  if (array && size > 0) {
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(size),
                            reinterpret_cast<const jbyte *>(data));
  }
  return array;
}

static jbyteArray newByteArray(JNIEnv *env, const std::string &data) {
  return newByteArray(env, data.data(), data.size());
}

static jbyteArray newByteArray(JNIEnv *env, const std::vector<uint8_t> &data) {
  return newByteArray(env, data.data(), data.size());
}

static jbyteArray transportFailure(JNIEnv *env, const char *message) {
  return newByteArray(
      env, wasmline::WasmlineResponseCodec::failure(
               wasmline::WasmlineErrorCode::TRANSPORT_FAILURE, message));
}

static jbyteArray rawFailure(JNIEnv *env, wasmline::WasmlineErrorCode code,
                             const char *message) {
  return newByteArray(env,
                      wasmline::TypedInvocationCodec::encodeResult(
                          wasmline::InvocationResult::failure(code, message),
                          wasmline::TypedInvocationKind::RAW));
}

static jboolean loadComponentWithFormatCommon(JNIEnv *env, jstring keyStr,
                                              jstring pathStr, jint formatCode,
                                              bool unsafe) {
  wasmline::WasmlineArtifactFormat artifactFormat;
  if (!wasmline::Api::tryArtifactFormatFromCode(
          static_cast<int32_t>(formatCode), &artifactFormat)) {
    LOGE("[Wasmline] JNI --> Invalid native artifact format code: %d",
         static_cast<int>(formatCode));
    return JNI_FALSE;
  }
  if (!env || !keyStr || !pathStr)
    return JNI_FALSE;
  const char *key = env->GetStringUTFChars(keyStr, nullptr);
  const char *path = env->GetStringUTFChars(pathStr, nullptr);
  if (!key || !path) {
    if (path)
      env->ReleaseStringUTFChars(pathStr, path);
    if (key)
      env->ReleaseStringUTFChars(keyStr, key);
    return JNI_FALSE;
  }
  bool success =
      unsafe ? wasmline::Api::loadComponentUnsafe(key, path, artifactFormat)
             : wasmline::Api::loadComponent(key, path, artifactFormat);
  env->ReleaseStringUTFChars(keyStr, key);
  env->ReleaseStringUTFChars(pathStr, path);
  return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" {

JNIEXPORT jboolean JNICALL Java_crow_wasmline_JniWasmlineBindings_nativeWarmUp(
    JNIEnv *env, jclass thiz, jboolean usePulley) {
  return wasmline::Api::warmupEngine(usePulley == JNI_TRUE) ? JNI_TRUE
                                                            : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeRuntimeIdentity(JNIEnv *env,
                                                             jclass thiz,
                                                             jint field) {
  const auto *identity = wasmline_get_native_runtime_identity();
  if (!env || !identity)
    return nullptr;
  const char *value = "";
  switch (field) {
  case 0:
    value = identity->cranelift_aot_compatibility_profile_id;
    break;
  case 1:
    value = identity->pulley_aot_compatibility_profile_id;
    break;
  case 2:
    value = identity->wasmline_release_version;
    break;
  case 3:
    value = identity->operating_system;
    break;
  case 4:
    value = identity->architecture;
    break;
  case 5:
    value = identity->supported_cpu_feature_profiles;
    break;
  case 6:
    value = identity->wasmtime_version;
    break;
  default:
    return nullptr;
  }
  return env->NewStringUTF(value);
}

JNIEXPORT jint JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeRuntimeIdentityInt(JNIEnv *env,
                                                                jclass thiz,
                                                                jint field) {
  const auto *identity = wasmline_get_native_runtime_identity();
  if (!identity)
    return 0;
  switch (field) {
  case 0:
    return identity->backend;
  case 1:
    return static_cast<jint>(identity->supported_artifact_formats);
  case 2:
    return identity->native_bridge_abi_version;
  case 3:
    return identity->pointer_width;
  default:
    return 0;
  }
}

JNIEXPORT void JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeReleaseEngine(JNIEnv *env,
                                                           jclass thiz) {
  wasmline::Api::releaseEngine();
}

JNIEXPORT void JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeResetAotLoadPathDiagnostics(
    JNIEnv *env, jclass thiz) {
  wasmline::AotLoadPathDiagnostics::reset();
}

JNIEXPORT jlong JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeAotLoadPathDiagnostics(
    JNIEnv *env, jclass thiz) {
  return static_cast<jlong>(wasmline::AotLoadPathDiagnostics::snapshot());
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeLoadAotWithFormat(
    JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr,
    jint formatCode) {
  return loadPrecompiledModuleWithFormatCommon(env, keyStr, pathStr, formatCode,
                                               false);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeLoadAotUnsafeWithFormat(
    JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr,
    jint formatCode) {
  return loadPrecompiledModuleWithFormatCommon(env, keyStr, pathStr, formatCode,
                                               true);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeLoadComponentWithFormat(
    JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr,
    jint formatCode) {
  return loadComponentWithFormatCommon(env, keyStr, pathStr, formatCode, false);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeLoadComponentUnsafeWithFormat(
    JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr,
    jint formatCode) {
  return loadComponentWithFormatCommon(env, keyStr, pathStr, formatCode, true);
}

JNIEXPORT void JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeReleaseModule(JNIEnv *env,
                                                           jclass thiz,
                                                           jstring keyStr) {
  if (!env || !keyStr)
    return;
  const char *key = env->GetStringUTFChars(keyStr, nullptr);
  if (!key)
    return;
  wasmline::Api::releaseModule(key);
  env->ReleaseStringUTFChars(keyStr, key);
}

JNIEXPORT jbyteArray JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeInvokeInbound(
    JNIEnv *env, jclass thiz, jstring keyStr, jstring actionStr,
    jbyteArray inputBytes) {
  if (!keyStr || !actionStr || !inputBytes) {
    return transportFailure(env,
                            "JNI inbound invocation received a null input.");
  }

  const char *key = env->GetStringUTFChars(keyStr, nullptr);
  const char *action = env->GetStringUTFChars(actionStr, nullptr);
  jsize actionLen = env->GetStringUTFLength(actionStr);
  if (!key || !action || actionLen < 0) {
    if (action)
      env->ReleaseStringUTFChars(actionStr, action);
    if (key)
      env->ReleaseStringUTFChars(keyStr, key);
    return transportFailure(
        env, "JNI inbound invocation could not read its string input.");
  }

  jsize dataLen = env->GetArrayLength(inputBytes);
  jbyte *dataPtr =
      dataLen == 0 ? nullptr : env->GetByteArrayElements(inputBytes, nullptr);
  if (dataLen < 0 || (dataLen > 0 && !dataPtr)) {
    if (dataPtr)
      env->ReleaseByteArrayElements(inputBytes, dataPtr, JNI_ABORT);
    env->ReleaseStringUTFChars(actionStr, action);
    env->ReleaseStringUTFChars(keyStr, key);
    return transportFailure(
        env, "JNI inbound invocation could not read its byte input.");
  }

  std::string resultData = wasmline::Api::invokeInbound(
      key, action, (size_t)actionLen, (const char *)dataPtr, (size_t)dataLen);

  if (dataPtr)
    env->ReleaseByteArrayElements(inputBytes, dataPtr, JNI_ABORT);
  env->ReleaseStringUTFChars(actionStr, action);
  env->ReleaseStringUTFChars(keyStr, key);

  return newByteArray(env, resultData);
}

static jbyteArray invokeTypedCommon(JNIEnv *env, jstring keyStr,
                                    jstring exportStr, jbyteArray inputBytes,
                                    wasmline::TypedInvocationKind kind,
                                    bool componentInstance = false) {
  wasmline::InvocationResult result = wasmline::InvocationResult::failure(
      wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
      "Typed invocation could not be executed.");

  const char *key = keyStr ? env->GetStringUTFChars(keyStr, nullptr) : nullptr;
  const char *exportName =
      exportStr ? env->GetStringUTFChars(exportStr, nullptr) : nullptr;
  jsize inputLen = 0;
  jbyte *input = nullptr;
  std::string inputData;
  const bool hasValidObjects =
      keyStr && exportStr && inputBytes && key && exportName;
  if (hasValidObjects) {
    inputLen = env->GetArrayLength(inputBytes);
    input =
        inputLen > 0 ? env->GetByteArrayElements(inputBytes, nullptr) : nullptr;
    if (inputLen < 0 || (inputLen > 0 && !input)) {
      result = wasmline::InvocationResult::failure(
          wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
          "JNI typed invocation could not read its byte input.");
    } else {
      inputData = inputLen == 0
                      ? std::string()
                      : std::string(reinterpret_cast<const char *>(input),
                                    static_cast<size_t>(inputLen));
    }
  } else {
    result = wasmline::InvocationResult::failure(
        wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
        "JNI typed invocation received a null input.");
  }

  if (hasValidObjects && inputLen >= 0 && (inputLen == 0 || input != nullptr)) {
    std::string decodeError;
    if (kind == wasmline::TypedInvocationKind::RAW) {
      std::vector<wasmline::RawValue> arguments;
      if (!wasmline::TypedInvocationCodec::decodeRawArguments(
              inputData, &arguments, &decodeError)) {
        result = wasmline::InvocationResult::failure(
            wasmline::WasmlineErrorCode::INVALID_PAYLOAD,
            decodeError.empty() ? "Raw invocation payload is invalid."
                                : decodeError);
      } else {
        result = wasmline::Api::invokeRaw(key, exportName, arguments);
      }
    } else {
      std::vector<wasmline::ComponentValue> arguments;
      if (!wasmline::TypedInvocationCodec::decodeComponentArguments(
              inputData, &arguments, &decodeError)) {
        result = wasmline::InvocationResult::failure(
            wasmline::WasmlineErrorCode::INVALID_PAYLOAD,
            decodeError.empty() ? "Component invocation payload is invalid."
                                : decodeError);
      } else {
        result =
            componentInstance
                ? wasmline::Api::invokeComponentInstance(key, exportName,
                                                         arguments)
                : wasmline::Api::invokeComponent(key, exportName, arguments);
      }
    }
  }

  const std::vector<uint8_t> output =
      wasmline::TypedInvocationCodec::encodeResult(result, kind);
  if (input)
    env->ReleaseByteArrayElements(inputBytes, input, JNI_ABORT);
  if (exportName)
    env->ReleaseStringUTFChars(exportStr, exportName);
  if (key)
    env->ReleaseStringUTFChars(keyStr, key);

  return newByteArray(env, output);
}

JNIEXPORT jbyteArray JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeInvokeRaw(JNIEnv *env, jclass thiz,
                                                       jstring keyStr,
                                                       jstring exportStr,
                                                       jbyteArray inputBytes) {
  return invokeTypedCommon(env, keyStr, exportStr, inputBytes,
                           wasmline::TypedInvocationKind::RAW);
}

JNIEXPORT jbyteArray JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeCoreModuleExports(JNIEnv *env,
                                                               jclass thiz,
                                                               jstring keyStr) {
  if (!env || !keyStr)
    return nullptr;
  const char *key = env->GetStringUTFChars(keyStr, nullptr);
  if (!key)
    return nullptr;
  size_t length = 0;
  char *bytes = wasmline_core_module_exports(key, &length);
  env->ReleaseStringUTFChars(keyStr, key);
  if (!bytes)
    return nullptr;
  jbyteArray result = newByteArray(env, bytes, length);
  wasmline_free_memory(bytes);
  return result;
}

JNIEXPORT jbyteArray JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeCoreCreateSession(
    JNIEnv *env, jclass thiz, jstring artifactKeyStr, jstring sessionKeyStr,
    jbyteArray importsBytes, jobject dispatcher, jstring memoryExportNameStr) {
  if (!env || !artifactKeyStr || !sessionKeyStr || !importsBytes ||
      !dispatcher) {
    return transportFailure(env, "JNI Core session received a null input.");
  }
  const char *artifactKey = env->GetStringUTFChars(artifactKeyStr, nullptr);
  const char *sessionKey = env->GetStringUTFChars(sessionKeyStr, nullptr);
  const char *memoryExportName =
      memoryExportNameStr ? env->GetStringUTFChars(memoryExportNameStr, nullptr)
                          : nullptr;
  jsize importsLength = env->GetArrayLength(importsBytes);
  jbyte *imports = importsLength > 0
                       ? env->GetByteArrayElements(importsBytes, nullptr)
                       : nullptr;
  if (!artifactKey || !sessionKey || (importsLength > 0 && !imports) ||
      (memoryExportNameStr && !memoryExportName)) {
    if (imports)
      env->ReleaseByteArrayElements(importsBytes, imports, JNI_ABORT);
    if (memoryExportName)
      env->ReleaseStringUTFChars(memoryExportNameStr, memoryExportName);
    if (sessionKey)
      env->ReleaseStringUTFChars(sessionKeyStr, sessionKey);
    if (artifactKey)
      env->ReleaseStringUTFChars(artifactKeyStr, artifactKey);
    return transportFailure(env, "JNI Core session input could not be read.");
  }
  auto handler = std::make_unique<JniRawImportHandler>(env, dispatcher);
  std::vector<uint8_t> response;
  if (!handler->isValid()) {
    response = wasmline::TypedInvocationCodec::encodeResult(
        wasmline::InvocationResult::failure(
            wasmline::WasmlineErrorCode::IMPORT_HANDLER_FAILED,
            "JNI raw import dispatcher is not initialized."),
        wasmline::TypedInvocationKind::RAW);
  } else {
    size_t outLength = 0;
    JniRawImportHandler *rawHandler = handler.release();
    char *raw = wasmline_core_create_session(
        artifactKey, sessionKey, importsLength == 0 ? nullptr : imports,
        static_cast<size_t>(importsLength), &JniRawImportHandler::callback,
        &JniRawImportHandler::freeBuffer, rawHandler,
        &JniRawImportHandler::finalize, memoryExportName, &outLength);
    if (raw && outLength > 0) {
      response.assign(reinterpret_cast<uint8_t *>(raw),
                      reinterpret_cast<uint8_t *>(raw) + outLength);
      wasmline_free_memory(raw);
    } else {
      response = wasmline::TypedInvocationCodec::encodeResult(
          wasmline::InvocationResult::failure(
              wasmline::WasmlineErrorCode::INSTANTIATION_FAILED,
              "Native Core session creation returned no response."),
          wasmline::TypedInvocationKind::RAW);
    }
  }
  if (imports)
    env->ReleaseByteArrayElements(importsBytes, imports, JNI_ABORT);
  if (memoryExportName)
    env->ReleaseStringUTFChars(memoryExportNameStr, memoryExportName);
  env->ReleaseStringUTFChars(sessionKeyStr, sessionKey);
  env->ReleaseStringUTFChars(artifactKeyStr, artifactKey);
  return newByteArray(env, response);
}

JNIEXPORT jbyteArray JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeCoreInvoke(JNIEnv *env,
                                                        jclass thiz,
                                                        jstring sessionKeyStr,
                                                        jstring exportStr,
                                                        jbyteArray inputBytes) {
  if (!env || !sessionKeyStr || !exportStr || !inputBytes)
    return nullptr;
  const char *sessionKey = env->GetStringUTFChars(sessionKeyStr, nullptr);
  const char *exportName = env->GetStringUTFChars(exportStr, nullptr);
  const jsize length = env->GetArrayLength(inputBytes);
  jbyte *input =
      length > 0 ? env->GetByteArrayElements(inputBytes, nullptr) : nullptr;
  if (!sessionKey || !exportName || (length > 0 && !input)) {
    if (input)
      env->ReleaseByteArrayElements(inputBytes, input, JNI_ABORT);
    if (exportName)
      env->ReleaseStringUTFChars(exportStr, exportName);
    if (sessionKey)
      env->ReleaseStringUTFChars(sessionKeyStr, sessionKey);
    return nullptr;
  }
  size_t outLength = 0;
  char *output = wasmline_core_invoke(sessionKey, exportName,
                                      env->GetStringUTFLength(exportStr), input,
                                      static_cast<size_t>(length), &outLength);
  if (input)
    env->ReleaseByteArrayElements(inputBytes, input, JNI_ABORT);
  env->ReleaseStringUTFChars(exportStr, exportName);
  env->ReleaseStringUTFChars(sessionKeyStr, sessionKey);
  if (!output)
    return nullptr;
  jbyteArray result = newByteArray(env, output, outLength);
  wasmline_free_memory(output);
  return result;
}

JNIEXPORT void JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeCoreReleaseSession(
    JNIEnv *env, jclass thiz, jstring sessionKeyStr) {
  if (!env || !sessionKeyStr)
    return;
  const char *sessionKey = env->GetStringUTFChars(sessionKeyStr, nullptr);
  if (!sessionKey)
    return;
  wasmline_core_release_session(sessionKey);
  env->ReleaseStringUTFChars(sessionKeyStr, sessionKey);
}

static jbyteArray coreMemoryScalarCarrier(JNIEnv *env, jstring sessionKeyStr,
                                          jboolean pages, jlong delta,
                                          bool grow) {
  if (!env)
    return nullptr;
  if (!sessionKeyStr || delta < 0) {
    return rawFailure(
        env, wasmline::WasmlineErrorCode::MEMORY_OUT_OF_BOUNDS,
        "JNI raw memory scalar operation received an invalid input.");
  }
  const char *sessionKey = env->GetStringUTFChars(sessionKeyStr, nullptr);
  if (!sessionKey) {
    return rawFailure(env, wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
                      "JNI raw memory session key could not be read.");
  }
  size_t outLength = 0;
  char *output =
      grow ? wasmline_core_memory_grow(sessionKey, static_cast<uint64_t>(delta),
                                       &outLength)
           : wasmline_core_memory_size(sessionKey, pages == JNI_TRUE,
                                       &outLength);
  env->ReleaseStringUTFChars(sessionKeyStr, sessionKey);
  if (!output) {
    return rawFailure(env, wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
                      "JNI raw memory scalar operation returned no response.");
  }
  jbyteArray result = newByteArray(env, output, outLength);
  wasmline_free_memory(output);
  return result;
}

static jbyteArray coreMemoryTransfer(JNIEnv *env, jstring sessionKeyStr,
                                     jlong memoryOffset, jbyteArray buffer,
                                     jint bufferOffset, jint length,
                                     bool read) {
  if (!env)
    return nullptr;
  if (!sessionKeyStr || !buffer) {
    return rawFailure(env, wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
                      "JNI raw memory transfer received a null input.");
  }
  const jsize bufferSize = env->GetArrayLength(buffer);
  if (memoryOffset < 0 || bufferOffset < 0 || length < 0 ||
      bufferOffset > bufferSize || length > bufferSize - bufferOffset) {
    return rawFailure(env, wasmline::WasmlineErrorCode::MEMORY_OUT_OF_BOUNDS,
                      "JNI raw memory transfer range is invalid.");
  }

  const char *sessionKey = env->GetStringUTFChars(sessionKeyStr, nullptr);
  if (!sessionKey) {
    return rawFailure(env, wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
                      "JNI raw memory session key could not be read.");
  }
  jbyte *data =
      length > 0 ? env->GetByteArrayElements(buffer, nullptr) : nullptr;
  if (length > 0 && !data) {
    env->ReleaseStringUTFChars(sessionKeyStr, sessionKey);
    return rawFailure(env, wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
                      "JNI raw memory buffer could not be accessed.");
  }

  bool success = false;
  size_t failureLength = 0;
  void *range = data ? static_cast<void *>(data + bufferOffset) : nullptr;
  char *failure =
      read ? wasmline_core_memory_read_into(
                 sessionKey, static_cast<uint64_t>(memoryOffset), range,
                 static_cast<uint64_t>(length), &success, &failureLength)
           : wasmline_core_memory_write_from(
                 sessionKey, static_cast<uint64_t>(memoryOffset), range,
                 static_cast<uint64_t>(length), &success, &failureLength);
  if (data)
    env->ReleaseByteArrayElements(buffer, data,
                                  read && success ? 0 : JNI_ABORT);
  env->ReleaseStringUTFChars(sessionKeyStr, sessionKey);

  if (success) {
    if (failure)
      wasmline_free_memory(failure);
    return nullptr;
  }
  if (!failure) {
    return rawFailure(
        env, wasmline::WasmlineErrorCode::TRANSPORT_FAILURE,
        "Native raw memory transfer returned no failure response.");
  }
  jbyteArray result = newByteArray(env, failure, failureLength);
  wasmline_free_memory(failure);
  return result;
}

JNIEXPORT jbyteArray JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeCoreMemorySize(
    JNIEnv *env, jclass thiz, jstring sessionKeyStr, jboolean pages) {
  return coreMemoryScalarCarrier(env, sessionKeyStr, pages, 0, false);
}

JNIEXPORT jbyteArray JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeCoreMemoryReadInto(
    JNIEnv *env, jclass thiz, jstring sessionKeyStr, jlong sourceOffset,
    jbyteArray destination, jint destinationOffset, jint length) {
  return coreMemoryTransfer(env, sessionKeyStr, sourceOffset, destination,
                            destinationOffset, length, true);
}

JNIEXPORT jbyteArray JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeCoreMemoryWriteFrom(
    JNIEnv *env, jclass thiz, jstring sessionKeyStr, jbyteArray source,
    jint sourceOffset, jlong destinationOffset, jint length) {
  return coreMemoryTransfer(env, sessionKeyStr, destinationOffset, source,
                            sourceOffset, length, false);
}

JNIEXPORT jbyteArray JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeCoreMemoryGrow(
    JNIEnv *env, jclass thiz, jstring sessionKeyStr, jlong deltaPages) {
  return coreMemoryScalarCarrier(env, sessionKeyStr, JNI_FALSE, deltaPages,
                                 true);
}

JNIEXPORT jbyteArray JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeInvokeComponent(
    JNIEnv *env, jclass thiz, jstring keyStr, jstring exportStr,
    jbyteArray inputBytes) {
  return invokeTypedCommon(env, keyStr, exportStr, inputBytes,
                           wasmline::TypedInvocationKind::COMPONENT);
}

JNIEXPORT jbyteArray JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeInvokeComponentInstance(
    JNIEnv *env, jclass thiz, jstring instanceKeyStr, jstring exportStr,
    jbyteArray inputBytes) {
  return invokeTypedCommon(env, instanceKeyStr, exportStr, inputBytes,
                           wasmline::TypedInvocationKind::COMPONENT, true);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeInstantiateComponent(
    JNIEnv *env, jclass thiz, jstring artifactKeyStr, jstring instanceKeyStr,
    jobject jDispatcher) {
  if (!env || !artifactKeyStr || !instanceKeyStr || !jDispatcher)
    return JNI_FALSE;
  const char *artifactKey = env->GetStringUTFChars(artifactKeyStr, nullptr);
  const char *instanceKey = env->GetStringUTFChars(instanceKeyStr, nullptr);
  if (!artifactKey || !instanceKey) {
    if (instanceKey)
      env->ReleaseStringUTFChars(instanceKeyStr, instanceKey);
    if (artifactKey)
      env->ReleaseStringUTFChars(artifactKeyStr, artifactKey);
    return JNI_FALSE;
  }

  auto handler = std::make_unique<JniComponentHostHandler>(env, jDispatcher);
  const bool instantiated =
      handler->isValid() && wasmline::Api::instantiateComponent(
                                artifactKey, instanceKey, std::move(handler));
  env->ReleaseStringUTFChars(instanceKeyStr, instanceKey);
  env->ReleaseStringUTFChars(artifactKeyStr, artifactKey);
  return instantiated ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeReleaseComponentInstance(
    JNIEnv *env, jclass thiz, jstring instanceKeyStr) {
  if (!env || !instanceKeyStr)
    return;
  const char *instanceKey = env->GetStringUTFChars(instanceKeyStr, nullptr);
  if (!instanceKey)
    return;
  wasmline::Api::releaseComponentInstance(instanceKey);
  env->ReleaseStringUTFChars(instanceKeyStr, instanceKey);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeDropComponentResource(
    JNIEnv *env, jclass thiz, jstring instanceKeyStr,
    jbyteArray resourceBytes) {
  if (!env || !instanceKeyStr || !resourceBytes)
    return JNI_FALSE;
  const char *instanceKey = env->GetStringUTFChars(instanceKeyStr, nullptr);
  if (!instanceKey)
    return JNI_FALSE;
  const jsize length = env->GetArrayLength(resourceBytes);
  jbyte *bytes = env->GetByteArrayElements(resourceBytes, nullptr);
  if (length > 0 && !bytes) {
    env->ReleaseStringUTFChars(instanceKeyStr, instanceKey);
    return JNI_FALSE;
  }
  const auto *input = reinterpret_cast<const uint8_t *>(bytes);
  std::vector<wasmline::ComponentValue> values;
  std::string error;
  const bool decoded = wasmline::TypedInvocationCodec::decodeComponentArguments(
      std::string_view(reinterpret_cast<const char *>(input),
                       static_cast<size_t>(length)),
      &values, &error);
  const bool dropped =
      decoded && values.size() == 1 &&
      values.front().kind() == wasmline::ComponentValue::Kind::RESOURCE &&
      wasmline::Api::dropComponentResource(instanceKey,
                                           values.front().resourceValue());
  if (bytes)
    env->ReleaseByteArrayElements(resourceBytes, bytes, JNI_ABORT);
  env->ReleaseStringUTFChars(instanceKeyStr, instanceKey);
  return dropped ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeCreateComponentHostResource(
    JNIEnv *env, jclass thiz, jstring instanceKeyStr, jstring interfaceIdStr,
    jstring resourceNameStr, jint representation) {
  if (!env || !instanceKeyStr || !interfaceIdStr || !resourceNameStr ||
      representation == 0)
    return nullptr;
  const char *instanceKey = env->GetStringUTFChars(instanceKeyStr, nullptr);
  const char *interfaceId = env->GetStringUTFChars(interfaceIdStr, nullptr);
  const char *resourceName = env->GetStringUTFChars(resourceNameStr, nullptr);
  if (!instanceKey || !interfaceId || !resourceName) {
    if (resourceName)
      env->ReleaseStringUTFChars(resourceNameStr, resourceName);
    if (interfaceId)
      env->ReleaseStringUTFChars(interfaceIdStr, interfaceId);
    if (instanceKey)
      env->ReleaseStringUTFChars(instanceKeyStr, instanceKey);
    return nullptr;
  }
  wasmline::ComponentResourceReference reference;
  const bool created = wasmline::Api::createComponentHostResource(
      instanceKey, interfaceId, resourceName,
      static_cast<uint32_t>(representation), &reference);
  env->ReleaseStringUTFChars(resourceNameStr, resourceName);
  env->ReleaseStringUTFChars(interfaceIdStr, interfaceId);
  env->ReleaseStringUTFChars(instanceKeyStr, instanceKey);
  if (!created)
    return nullptr;
  return newByteArray(
      env, wasmline::TypedInvocationCodec::encodeComponentArguments(
               {wasmline::ComponentValue::resource(std::move(reference))}));
}

JNIEXPORT void JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeSetOutboundHandler(
    JNIEnv *env, jclass thiz, jstring keyStr, jstring codecStr,
    jobject jDispatcher) {
  if (!keyStr || !codecStr || !jDispatcher)
    return;
  const char *key = env->GetStringUTFChars(keyStr, nullptr);
  const char *codec = env->GetStringUTFChars(codecStr, nullptr);
  if (!key || !codec) {
    if (codec)
      env->ReleaseStringUTFChars(codecStr, codec);
    if (key)
      env->ReleaseStringUTFChars(keyStr, key);
    return;
  }
  auto handler = std::make_unique<JniHostHandler>(env, jDispatcher);
  if (!handler->isValid()) {
    env->ReleaseStringUTFChars(codecStr, codec);
    env->ReleaseStringUTFChars(keyStr, key);
    return;
  }
  wasmline::Api::setOutboundHandler(key, codec, std::move(handler));
  env->ReleaseStringUTFChars(codecStr, codec);
  env->ReleaseStringUTFChars(keyStr, key);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_JniWasmlineBindings_nativeSetComponentHostHandler(
    JNIEnv *env, jclass thiz, jstring keyStr, jobject jDispatcher) {
  if (!env || !keyStr || !jDispatcher)
    return JNI_FALSE;
  const char *key = env->GetStringUTFChars(keyStr, nullptr);
  if (!key)
    return JNI_FALSE;

  auto handler = std::make_unique<JniComponentHostHandler>(env, jDispatcher);
  if (!handler->isValid()) {
    env->ReleaseStringUTFChars(keyStr, key);
    return JNI_FALSE;
  }
  const bool installed =
      wasmline::Api::setComponentHostHandler(key, std::move(handler));
  env->ReleaseStringUTFChars(keyStr, key);
  return installed ? JNI_TRUE : JNI_FALSE;
}
}
