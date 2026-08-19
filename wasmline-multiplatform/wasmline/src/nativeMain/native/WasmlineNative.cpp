/**
 * Implements the portable Kotlin/Native C bridge for the Wasmline native API.
 *
 * Date: 2026-08-19
 * Author: crowforkotlin
 */
#include "WasmlineNative.h"
#include "logging/NativeLogger.h"
#include "wasmline/api/Api.h"
#include "wasmline/invocation/TypedInvocationCodec.h"
#include "wasmline/protocol/WasmlineProtocol.h"
#include "wasmline/runtime/ComponentHostHandler.h"
#include "wasmline/runtime/OutboundHandler.h"
#include "io/FileIO.h"
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
} // namespace

extern "C" {

bool wasmline_warmup_engine(bool usePulley) { return Api::warmupEngine(usePulley); }

void wasmline_release_engine() { Api::releaseEngine(); }

const char *wasmline_wasmtime_version() { return Api::wasmtimeVersion(); }

bool wasmline_supports_cranelift() { return Api::supportsCranelift(); }

bool wasmline_supports_pulley() { return Api::supportsPulley(); }

void wasmline_native_engine_link_anchor() {}

const char *wasmline_target_os() {
#if defined(__ENVIRONMENT_IPHONE_OS_VERSION_MIN_REQUIRED__) || \
    defined(__ENVIRONMENT_TV_OS_VERSION_MIN_REQUIRED__)
  return "ios";
#elif defined(__APPLE__)
  return "macos";
#elif defined(_WIN32)
  return "windows";
#elif defined(__linux__)
  return "linux";
#else
  return "unknown";
#endif
}

const char *wasmline_target_cpu() {
#if defined(__aarch64__) || defined(_M_ARM64)
  return "aarch64";
#elif defined(__x86_64__) || defined(_M_X64)
  return "x86_64";
#elif defined(__i386__) || defined(_M_IX86)
  return "x86";
#elif defined(__arm__) || defined(_M_ARM)
  return "arm";
#else
  return "unknown";
#endif
}

bool wasmline_target_is_64_bit() { return sizeof(void *) == 8; }

bool wasmline_path_exists(const char *path) {
  if (!path)
    return false;
  return io::exists(std::string(path));
}

void wasmline_lock() { nativeBridgeMutex().lock(); }

void wasmline_unlock() { nativeBridgeMutex().unlock(); }

bool wasmline_load_module_with_format(const char *key, const char *path,
                                      int32_t formatCode, bool isUnsafe) {
  WasmlineArtifactFormat artifactFormat;
  if (!Api::tryArtifactFormatFromCode(formatCode, &artifactFormat)) {
    LOGE("[Wasmline] Native --> Invalid artifact format code: %d",
         static_cast<int>(formatCode));
    return false;
  }
  if (!key || !path)
    return false;
  if (isUnsafe) {
    return Api::loadModuleUnsafe(std::string(key), std::string(path),
                                 artifactFormat);
  }
  return Api::loadModule(std::string(key), std::string(path), artifactFormat);
}

bool wasmline_load_component_with_format(const char *key, const char *path,
                                         int32_t formatCode, bool isUnsafe) {
  WasmlineArtifactFormat artifactFormat;
  if (!Api::tryArtifactFormatFromCode(formatCode, &artifactFormat)) {
    LOGE("[Wasmline] Native --> Invalid artifact format code: %d",
         static_cast<int>(formatCode));
    return false;
  }
  if (!key || !path)
    return false;
  if (isUnsafe) {
    return Api::loadComponentUnsafe(std::string(key), std::string(path),
                                    artifactFormat);
  }
  return Api::loadComponent(std::string(key), std::string(path),
                            artifactFormat);
}

void wasmline_release_module(const char *key) {
  if (!key)
    return;
  Api::releaseModule(std::string(key));
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
