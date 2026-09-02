/**
 * Implements the portable Kotlin/Native C bridge for the Wasmline native API.
 *
 * Date: 2026-08-19
 * Author: crowforkotlin
 */
#include "WasmlineNative.h"
#include "wasmline/api/Api.h"
#include "wasmline/invocation/CoreWasmBridgeCodec.h"
#include "wasmline/invocation/TypedInvocationCodec.h"
#include "wasmline/protocol/WasmlineProtocol.h"
#include "wasmline/runtime/ComponentHostHandler.h"
#include "wasmline/runtime/OutboundHandler.h"
#include "wasmline/internal/io/FileIO.h"
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

using namespace wasmline;

/** Forwards outbound calls to the Kotlin callback. */
class NativeOutboundHandler : public OutboundHandler {
private:
  std::string key;
  OutboundCallback kotlinCallback;

public:
  /** Creates a handler for the Kotlin callback. */
  NativeOutboundHandler(std::string key, OutboundCallback callback)
      : key(std::move(key)), kotlinCallback(callback) {}

  /** Sends an outbound call to Kotlin. */
  std::string onOutboundInvoke(std::string_view action,
                               std::string_view payload) override {
    if (kotlinCallback) {
      size_t resultLength = 0;
      char *resultRaw = kotlinCallback(key.data(), key.length(), action.data(),
                                       action.length(), payload.data(),
                                       payload.length(), &resultLength);
      if (resultRaw != nullptr) {
        std::string result(resultRaw, resultLength);
        wasmline_free_memory(resultRaw);
        return result;
      }
    }
    return WasmlineResponseCodec::failure(
        WasmlineErrorCode::ACTION_NOT_BOUND,
        "No Wasmline outbound action is bound.");
  }
};

/** Forwards typed Component host imports to the Kotlin callback. */
class NativeComponentHostHandler : public ComponentHostHandler {
private:
  std::string key;
  ComponentHostCallback kotlinCallback;

public:
  /** Creates a handler for one Kotlin typed Component callback. */
  NativeComponentHostHandler(std::string key, ComponentHostCallback callback)
      : key(std::move(key)), kotlinCallback(callback) {}

  /** Sends one typed Component import to Kotlin and decodes its response. */
  InvocationResult
  onComponentHostInvoke(std::string_view interfaceName,
                        std::string_view functionName,
                        const std::vector<ComponentValue> &arguments) override {
    if (!kotlinCallback) {
      return InvocationResult::failure(
          WasmlineErrorCode::HANDLER_FAILED,
          "Wasmline Native typed Component callback is not initialized.");
    }

    const std::vector<uint8_t> encodedArguments =
        TypedInvocationCodec::encodeComponentArguments(arguments);
    if (encodedArguments.empty()) {
      return InvocationResult::failure(
          WasmlineErrorCode::INVALID_PAYLOAD,
          "Typed Component host arguments cannot be encoded for Native.");
    }

    size_t responseLength = 0;
    const char *argumentData =
        reinterpret_cast<const char *>(encodedArguments.data());
    char *responseData = kotlinCallback(
        key.data(), key.size(), interfaceName.data(), interfaceName.size(),
        functionName.data(), functionName.size(), argumentData,
        encodedArguments.size(), &responseLength);
    if (!responseData) {
      return InvocationResult::failure(
          WasmlineErrorCode::ACTION_NOT_BOUND,
          "No typed Component host adapter is bound.");
    }

    std::string response(responseData, responseLength);
    wasmline_free_memory(responseData);

    InvocationResult result = InvocationResult::failure(
        WasmlineErrorCode::RESPONSE_MALFORMED,
        "Typed Component host response is malformed.");
    std::string decodeError;
    if (!TypedInvocationCodec::decodeComponentResult(response, &result,
                                                     &decodeError)) {
      return InvocationResult::failure(
          WasmlineErrorCode::RESPONSE_MALFORMED,
          decodeError.empty() ? "Typed Component host response is malformed."
                              : decodeError);
    }
    return result;
  }
};

namespace {
std::recursive_mutex &nativeBridgeMutex() {
  static std::recursive_mutex mutex;
  return mutex;
}

char *copyNativeBytes(const void *data, size_t size, size_t *outLen) {
  if (outLen)
    *outLen = 0;
  if (size == 0)
    return nullptr;
  char *output = wasmline_allocate_memory(size);
  if (!output)
    return nullptr;
  std::memcpy(output, data, size);
  if (outLen)
    *outLen = size;
  return output;
}

char *copyNativeBytes(const std::vector<uint8_t> &bytes, size_t *outLen) {
  return copyNativeBytes(bytes.data(), bytes.size(), outLen);
}

char *encodeArtifactLoadResult(const ArtifactLoadResult &result,
                               size_t *outLen) {
  const std::string encoded = result.isSuccess()
                                  ? WasmlineResponseCodec::success(std::string_view{})
                                  : WasmlineResponseCodec::failure(
                                        result.errorCode(), result.message(),
                                        result.details().empty()
                                            ? std::string_view{}
                                            : std::string_view(
                                                  reinterpret_cast<const char *>(
                                                      result.details().data()),
                                                  result.details().size()));
  return copyNativeBytes(encoded.data(), encoded.size(), outLen);
}
} // namespace

extern "C" {

bool wasmline_warmup_engine(bool usePulley) { return Api::warmupEngine(usePulley); }

void wasmline_release_engine() { Api::releaseEngine(); }

void wasmline_native_engine_link_anchor() {}

bool wasmline_path_exists(const char *path) {
  if (!path)
    return false;
  return io::exists(std::string(path));
}

void wasmline_lock() { nativeBridgeMutex().lock(); }

void wasmline_unlock() { nativeBridgeMutex().unlock(); }

char *wasmline_load_module_with_format(const char *key, const char *path,
                                       int32_t formatCode, bool isUnsafe,
                                       size_t *outLen) {
  WasmlineArtifactFormat artifactFormat;
  if (!Api::tryArtifactFormatFromCode(formatCode, &artifactFormat)) {
    return encodeArtifactLoadResult(
        ArtifactLoadResult::failure(
            WasmlineErrorCode::ARTIFACT_DESCRIPTOR_INVALID,
            "Native Core Wasm load received an invalid artifact format code."),
        outLen);
  }
  if (!key || !path) {
    return encodeArtifactLoadResult(
        ArtifactLoadResult::failure(
            WasmlineErrorCode::ARTIFACT_DESCRIPTOR_INVALID,
            "Native Core Wasm load received a null key or path."),
        outLen);
  }
  const ArtifactLoadResult result =
      isUnsafe ? Api::loadModuleUnsafe(std::string(key), std::string(path),
                                       artifactFormat)
               : Api::loadModule(std::string(key), std::string(path),
                                 artifactFormat);
  return encodeArtifactLoadResult(result, outLen);
}

char *wasmline_load_component_with_format(const char *key, const char *path,
                                          int32_t formatCode, bool isUnsafe,
                                          size_t *outLen) {
  WasmlineArtifactFormat artifactFormat;
  if (!Api::tryArtifactFormatFromCode(formatCode, &artifactFormat)) {
    return encodeArtifactLoadResult(
        ArtifactLoadResult::failure(
            WasmlineErrorCode::ARTIFACT_DESCRIPTOR_INVALID,
            "Native Component load received an invalid artifact format code."),
        outLen);
  }
  if (!key || !path) {
    return encodeArtifactLoadResult(
        ArtifactLoadResult::failure(
            WasmlineErrorCode::ARTIFACT_DESCRIPTOR_INVALID,
            "Native Component load received a null key or path."),
        outLen);
  }
  const ArtifactLoadResult result =
      isUnsafe ? Api::loadComponentUnsafe(std::string(key), std::string(path),
                                          artifactFormat)
               : Api::loadComponent(std::string(key), std::string(path),
                                    artifactFormat);
  return encodeArtifactLoadResult(result, outLen);
}

void wasmline_release_module(const char *key) {
  if (!key)
    return;
  Api::releaseModule(std::string(key));
}

static char *copyMemoryOperationFailure(const InvocationResult &result,
                                        bool *outSuccess, size_t *outLen) {
  if (outSuccess)
    *outSuccess = result.isSuccess();
  if (outLen)
    *outLen = 0;
  if (result.isSuccess())
    return nullptr;
  return copyNativeBytes(
      TypedInvocationCodec::encodeResult(result, TypedInvocationKind::RAW),
      outLen);
}

char *wasmline_core_module_exports(const char *key, size_t *outLen) {
  if (outLen)
    *outLen = 0;
  if (!key)
    return nullptr;
  return copyNativeBytes(CoreWasmBridgeCodec::encodeExports(Api::describeRawModule(key)), outLen);
}

char *wasmline_core_create_session(const char *artifactKey, const char *sessionKey,
                                  const void *imports, size_t importsLen,
                                  WasmlineRawImportCallback callback,
                                  WasmlineRawImportBufferFree bufferFree,
                                  void *callbackUser,
                                  WasmlineRawImportUserFinalizer userFinalizer,
                                  const char *memoryExportName, size_t *outLen) {
  if (outLen)
    *outLen = 0;
  InvocationResult result = InvocationResult::failure(
      WasmlineErrorCode::TRANSPORT_FAILURE,
      "Native Core Wasm session received invalid input.");
  if (!artifactKey || !sessionKey || (importsLen > 0 && !imports)) {
    if (userFinalizer && callbackUser)
      userFinalizer(callbackUser);
  } else {
    std::vector<RawImportDefinition> definitions;
    std::string error;
    const std::string_view encodedImports(
        static_cast<const char *>(imports), importsLen);
    if (!CoreWasmBridgeCodec::decodeImports(encodedImports, &definitions,
                                             &error)) {
      if (userFinalizer && callbackUser)
        userFinalizer(callbackUser);
      result = InvocationResult::failure(
          WasmlineErrorCode::INVALID_PAYLOAD,
          error.empty() ? "Raw import metadata is invalid." : error);
    } else {
      result = Api::instantiateRawModule(
          artifactKey, sessionKey, definitions,
          reinterpret_cast<RawImportCallback>(callback),
          reinterpret_cast<RawImportBufferFree>(bufferFree), callbackUser,
          reinterpret_cast<RawImportUserFinalizer>(userFinalizer),
          memoryExportName ? std::string(memoryExportName)
                           : std::string("memory"));
    }
  }
  return copyNativeBytes(
      TypedInvocationCodec::encodeResult(result, TypedInvocationKind::RAW),
      outLen);
}

char *wasmline_core_invoke(const char *sessionKey, const char *exportName,
                           size_t exportNameLen, const void *arguments,
                           size_t argumentsLen, size_t *outLen) {
  if (outLen)
    *outLen = 0;
  InvocationResult result = InvocationResult::failure(
      WasmlineErrorCode::TRANSPORT_FAILURE,
      "Native Core Wasm invocation received invalid input.");
  if (sessionKey && (exportNameLen == 0 || exportName) &&
      (argumentsLen == 0 || arguments)) {
    std::vector<RawValue> values;
    std::string error;
    const std::string_view encodedArguments(
        static_cast<const char *>(arguments), argumentsLen);
    if (!TypedInvocationCodec::decodeRawArguments(encodedArguments, &values,
                                                   &error)) {
      result = InvocationResult::failure(
          WasmlineErrorCode::INVALID_PAYLOAD,
          error.empty() ? "Raw Core Wasm arguments are invalid." : error);
    } else {
      result = Api::invokeRawInstance(
          sessionKey,
          exportNameLen == 0 ? std::string_view()
                             : std::string_view(exportName, exportNameLen),
          values);
    }
  }
  return copyNativeBytes(
      TypedInvocationCodec::encodeResult(result, TypedInvocationKind::RAW),
      outLen);
}

void wasmline_core_release_session(const char *sessionKey) {
  if (sessionKey)
    Api::releaseRawInstance(sessionKey);
}

char *wasmline_core_memory_size(const char *sessionKey, bool pages,
                                size_t *outLen) {
  if (outLen)
    *outLen = 0;
  InvocationResult result = sessionKey
                                ? Api::rawMemorySize(sessionKey, pages)
                                : InvocationResult::failure(
                                      WasmlineErrorCode::TRANSPORT_FAILURE,
                                      "Raw memory session key is null.");
  return copyNativeBytes(
      TypedInvocationCodec::encodeResult(result, TypedInvocationKind::RAW),
      outLen);
}

char *wasmline_core_memory_read_into(const char *sessionKey, uint64_t offset,
                                     void *destination, uint64_t length,
                                     bool *outSuccess, size_t *outLen) {
  InvocationResult result =
      sessionKey && (length == 0 || destination)
          ? Api::readRawMemory(sessionKey, offset,
                               static_cast<uint8_t *>(destination), length)
          : InvocationResult::failure(
                WasmlineErrorCode::TRANSPORT_FAILURE,
                "Raw memory read received invalid caller-owned storage.");
  return copyMemoryOperationFailure(result, outSuccess, outLen);
}

char *wasmline_core_memory_write_from(const char *sessionKey, uint64_t offset,
                                      const void *source, uint64_t length,
                                      bool *outSuccess, size_t *outLen) {
  InvocationResult result =
      sessionKey && (length == 0 || source)
          ? Api::writeRawMemory(sessionKey, offset,
                                static_cast<const uint8_t *>(source), length)
          : InvocationResult::failure(
                WasmlineErrorCode::TRANSPORT_FAILURE,
                "Raw memory write received invalid caller-owned storage.");
  return copyMemoryOperationFailure(result, outSuccess, outLen);
}

char *wasmline_core_memory_grow(const char *sessionKey, uint64_t deltaPages,
                                size_t *outLen) {
  if (outLen)
    *outLen = 0;
  InvocationResult result = sessionKey
                                ? Api::growRawMemory(sessionKey, deltaPages)
                                : InvocationResult::failure(
                                      WasmlineErrorCode::TRANSPORT_FAILURE,
                                      "Raw memory session key is null.");
  return copyNativeBytes(
      TypedInvocationCodec::encodeResult(result, TypedInvocationKind::RAW),
      outLen);
}

void wasmline_release_component_instance(const char *instanceKey) {
  if (!instanceKey)
    return;
  Api::releaseComponentInstance(std::string(instanceKey));
}

bool wasmline_drop_component_resource(const char *instanceKey, const void *data,
                                      size_t dataLen) {
  if (!instanceKey || (!data && dataLen != 0))
    return false;
  const std::string_view input =
      dataLen == 0 ? std::string_view()
                   : std::string_view(static_cast<const char *>(data), dataLen);
  std::vector<ComponentValue> values;
  std::string error;
  if (!TypedInvocationCodec::decodeComponentArguments(input, &values, &error) ||
      values.size() != 1 ||
      values.front().kind() != ComponentValue::Kind::RESOURCE) {
    return false;
  }
  return Api::dropComponentResource(std::string(instanceKey),
                                    values.front().resourceValue());
}

char *wasmline_create_component_host_resource(const char *instanceKey,
                                              const char *interfaceId,
                                              const char *resourceName,
                                              uint32_t representation,
                                              size_t *outLen) {
  if (outLen)
    *outLen = 0;
  if (!instanceKey || !interfaceId || !resourceName || representation == 0)
    return nullptr;
  ComponentResourceReference reference;
  if (!Api::createComponentHostResource(instanceKey, interfaceId, resourceName,
                                        representation, &reference))
    return nullptr;
  const auto encoded = TypedInvocationCodec::encodeComponentArguments(
      {ComponentValue::resource(std::move(reference))});
  if (encoded.empty())
    return nullptr;
  char *output = wasmline_allocate_memory(encoded.size());
  if (!output)
    return nullptr;
  memcpy(output, encoded.data(), encoded.size());
  if (outLen)
    *outLen = encoded.size();
  return output;
}

static char *invokeTyped(const char *key, const char *exportName,
                         size_t exportNameLen, const void *data, size_t dataLen,
                         size_t *outLen, TypedInvocationKind kind,
                         bool componentInstance = false) {
  if (outLen)
    *outLen = 0;
  InvocationResult result = InvocationResult::failure(
      WasmlineErrorCode::TRANSPORT_FAILURE,
      "Native typed invocation received an invalid native input.");

  const bool inputIsValid = key != nullptr &&
                            (exportNameLen == 0 || exportName != nullptr) &&
                            (dataLen == 0 || data != nullptr);
  if (inputIsValid) {
    const std::string input =
        dataLen == 0 ? std::string()
                     : std::string(static_cast<const char *>(data), dataLen);
    const std::string exportValue =
        exportNameLen == 0 ? std::string()
                           : std::string(exportName, exportNameLen);
    std::string error;
    if (kind == TypedInvocationKind::RAW) {
      std::vector<RawValue> arguments;
      if (!TypedInvocationCodec::decodeRawArguments(input, &arguments,
                                                    &error)) {
        result = InvocationResult::failure(
            WasmlineErrorCode::INVALID_PAYLOAD,
            error.empty() ? "Raw invocation payload is invalid." : error);
      } else {
        result = Api::invokeRaw(std::string(key), exportValue, arguments);
      }
    } else {
      std::vector<ComponentValue> arguments;
      if (!TypedInvocationCodec::decodeComponentArguments(input, &arguments,
                                                          &error)) {
        result = InvocationResult::failure(
            WasmlineErrorCode::INVALID_PAYLOAD,
            error.empty() ? "Component invocation payload is invalid." : error);
      } else {
        result = componentInstance
                     ? Api::invokeComponentInstance(std::string(key),
                                                    exportValue, arguments)
                     : Api::invokeComponent(std::string(key), exportValue,
                                            arguments);
      }
    }
  }

  const std::vector<uint8_t> encoded =
      TypedInvocationCodec::encodeResult(result, kind);
  if (outLen)
    *outLen = encoded.size();
  if (encoded.empty())
    return nullptr;
  char *output = wasmline_allocate_memory(encoded.size());
  if (!output) {
    if (outLen)
      *outLen = 0;
    return nullptr;
  }
  memcpy(output, encoded.data(), encoded.size());
  return output;
}

char *wasmline_invoke_raw(const char *key, const char *exportName,
                          size_t exportNameLen, const void *data,
                          size_t dataLen, size_t *outLen) {
  return invokeTyped(key, exportName, exportNameLen, data, dataLen, outLen,
                     TypedInvocationKind::RAW);
}

char *wasmline_invoke_component(const char *key, const char *exportName,
                                size_t exportNameLen, const void *data,
                                size_t dataLen, size_t *outLen) {
  return invokeTyped(key, exportName, exportNameLen, data, dataLen, outLen,
                     TypedInvocationKind::COMPONENT);
}

char *wasmline_invoke_component_instance(const char *instanceKey,
                                         const char *exportName,
                                         size_t exportNameLen, const void *data,
                                         size_t dataLen, size_t *outLen) {
  return invokeTyped(instanceKey, exportName, exportNameLen, data, dataLen,
                     outLen, TypedInvocationKind::COMPONENT, true);
}

char *wasmline_invoke_inbound(const char *key, const char *action,
                              size_t actionLen, const void *data,
                              size_t dataLen, size_t *outLen) {
  if (outLen)
    *outLen = 0;
  std::string resultData;
  if (!key || (actionLen > 0 && !action) || (dataLen > 0 && !data)) {
    resultData = WasmlineResponseCodec::failure(
        WasmlineErrorCode::TRANSPORT_FAILURE,
        "Native inbound invocation received an invalid native input.");
  } else {
    std::string strKey(key);
    std::string strAction =
        actionLen == 0 ? std::string() : std::string(action, actionLen);
    std::string strData =
        dataLen == 0 ? std::string()
                     : std::string(static_cast<const char *>(data), dataLen);
    resultData =
        Api::invokeInbound(strKey, strAction.c_str(), strAction.length(),
                           strData.c_str(), strData.length());
  }

  if (resultData.empty()) {
    return nullptr;
  }

  if (outLen)
    *outLen = resultData.size();

  char *cResult = wasmline_allocate_memory(resultData.size());
  if (cResult == nullptr) {
    if (outLen)
      *outLen = 0;
    return nullptr;
  }

  memcpy(cResult, resultData.data(), resultData.size());

  return cResult;
}

char *wasmline_allocate_memory(size_t size) {
  if (size == 0)
    return nullptr;
  return static_cast<char *>(std::malloc(size));
}

void wasmline_free_memory(char *str) {
  if (str)
    std::free(str);
}

void wasmline_set_outbound_handler(const char *key, const char *codec,
                                   OutboundCallback callback) {
  if (!key)
    return;
  std::unique_ptr<OutboundHandler> handler(
      new NativeOutboundHandler(key, callback));
  Api::setOutboundHandler(std::string(key),
                          codec ? std::string(codec) : std::string(),
                          std::move(handler));
}

bool wasmline_set_component_host_handler(const char *key,
                                         ComponentHostCallback callback) {
  if (!key || !callback)
    return false;
  std::unique_ptr<ComponentHostHandler> handler(
      new NativeComponentHostHandler(key, callback));
  return Api::setComponentHostHandler(std::string(key), std::move(handler));
}

bool wasmline_instantiate_component(const char *artifactKey,
                                    const char *instanceKey,
                                    ComponentHostCallback callback) {
  if (!artifactKey || !instanceKey || !callback)
    return false;
  std::unique_ptr<ComponentHostHandler> handler(
      new NativeComponentHostHandler(instanceKey, callback));
  return Api::instantiateComponent(
      std::string(artifactKey), std::string(instanceKey), std::move(handler));
}
}
