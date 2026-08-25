/**
 * Implements the native Wasmline API facade.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */

#include "wasmline/api/Api.h"

#include <utility>

#include "wasmline/internal/runtime/NativeRuntime.h"
#include "wasmline/runtime/ComponentHostHandler.h"
#include "wasmline/runtime/OutboundHandler.h"
#include <wasmtime.h>

namespace wasmline {
    bool Api::warmupEngine(bool usePulley) {
        return NativeRuntime::instance().warmup(usePulley);
    }

    const char* Api::wasmtimeVersion() {
        return WASMTIME_VERSION;
    }

    bool Api::supportsCranelift() {
#ifdef WASMTIME_FEATURE_CRANELIFT
        return true;
#else
        return false;
#endif
    }

    bool Api::supportsPulley() {
#ifdef WASMTIME_FEATURE_PULLEY
        return true;
#else
        return false;
#endif
    }

    void Api::releaseEngine() {
        NativeRuntime::instance().shutdown();
    }

    bool Api::tryArtifactFormatFromCode(int32_t formatCode, WasmlineArtifactFormat* format) {
        if (!format) return false;
        switch (formatCode) {
        case static_cast<int32_t>(WasmlineArtifactFormat::RAW_WASM):
            *format = WasmlineArtifactFormat::RAW_WASM;
            return true;
        case static_cast<int32_t>(WasmlineArtifactFormat::CWASM):
            *format = WasmlineArtifactFormat::CWASM;
            return true;
        case static_cast<int32_t>(WasmlineArtifactFormat::PWASM):
            *format = WasmlineArtifactFormat::PWASM;
            return true;
        default:
            return false;
        }
    }

    bool Api::loadModule(const std::string& key, const std::string& path, WasmlineArtifactFormat artifactFormat) {
        return NativeRuntime::instance().loadModule(key, path, artifactFormat, false);
    }

    bool Api::loadModuleUnsafe(const std::string& key, const std::string& path, WasmlineArtifactFormat artifactFormat) {
        return NativeRuntime::instance().loadModule(key, path, artifactFormat, true);
    }

    bool Api::loadComponent(const std::string& key, const std::string& path, WasmlineArtifactFormat artifactFormat) {
        return NativeRuntime::instance().loadComponent(key, path, artifactFormat, false);
    }

    bool Api::loadComponentUnsafe(const std::string& key, const std::string& path, WasmlineArtifactFormat artifactFormat) {
        return NativeRuntime::instance().loadComponent(key, path, artifactFormat, true);
    }

    void Api::releaseModule(const std::string& key) {
        NativeRuntime::instance().releaseArtifact(key);
    }

    std::string Api::invokeInbound(const std::string& key, const char* action, size_t actionLen, const char* data, size_t dataLen) {
        return NativeRuntime::instance().invokeInbound(key, action, actionLen, data, dataLen);
    }

    InvocationResult Api::invokeRaw(const std::string& key, std::string_view exportName, const std::vector<RawValue>& arguments) {
        return NativeRuntime::instance().invokeRaw(key, exportName, arguments);
    }

    std::vector<RawExportDefinition> Api::describeRawModule(const std::string& artifactKey) {
        return NativeRuntime::instance().describeRawModule(artifactKey);
    }

    InvocationResult Api::instantiateRawModule(const std::string& artifactKey, const std::string& sessionKey,
                                               const std::vector<RawImportDefinition>& imports, RawImportCallback callback,
                                               RawImportBufferFree bufferFree, void* callbackUser, RawImportUserFinalizer userFinalizer,
                                               std::string memoryExportName) {
        return NativeRuntime::instance().instantiateRawModule(artifactKey, sessionKey, imports, callback, bufferFree, callbackUser,
                                                              userFinalizer, std::move(memoryExportName));
    }

    InvocationResult Api::invokeRawInstance(const std::string& sessionKey, std::string_view exportName,
                                            const std::vector<RawValue>& arguments) {
        return NativeRuntime::instance().invokeRawInstance(sessionKey, exportName, arguments);
    }

    InvocationResult Api::readRawMemory(const std::string& sessionKey, uint64_t offset, uint64_t length, std::vector<uint8_t>* output) {
        return NativeRuntime::instance().readRawMemory(sessionKey, offset, length, output);
    }

    InvocationResult Api::writeRawMemory(const std::string& sessionKey, uint64_t offset, const uint8_t* bytes, uint64_t length) {
        return NativeRuntime::instance().writeRawMemory(sessionKey, offset, bytes, length);
    }

    InvocationResult Api::rawMemorySize(const std::string& sessionKey, bool pages) {
        return NativeRuntime::instance().rawMemorySize(sessionKey, pages);
    }

    InvocationResult Api::growRawMemory(const std::string& sessionKey, uint64_t deltaPages) {
        return NativeRuntime::instance().growRawMemory(sessionKey, deltaPages);
    }

    void Api::releaseRawInstance(const std::string& sessionKey) {
        NativeRuntime::instance().releaseRawInstance(sessionKey);
    }

    InvocationResult Api::invokeComponent(const std::string& key, std::string_view exportName,
                                          const std::vector<ComponentValue>& arguments) {
        return NativeRuntime::instance().invokeComponent(key, exportName, arguments);
    }

    bool Api::instantiateComponent(const std::string& artifactKey, const std::string& instanceKey,
                                   std::unique_ptr<ComponentHostHandler> handler) {
        return NativeRuntime::instance().instantiateComponent(artifactKey, instanceKey, std::move(handler));
    }

    InvocationResult Api::invokeComponentInstance(const std::string& instanceKey, std::string_view exportName,
                                                  const std::vector<ComponentValue>& arguments) {
        return NativeRuntime::instance().invokeComponentInstance(instanceKey, exportName, arguments);
    }

    bool Api::dropComponentResource(const std::string& instanceKey, const ComponentResourceReference& reference) {
        return NativeRuntime::instance().dropComponentResource(instanceKey, reference);
    }

    bool Api::createComponentHostResource(const std::string& instanceKey, std::string_view interfaceName, std::string_view resourceName,
                                          uint32_t representation, ComponentResourceReference* reference) {
        return NativeRuntime::instance().createComponentHostResource(instanceKey, interfaceName, resourceName, representation, reference);
    }

    void Api::releaseComponentInstance(const std::string& instanceKey) {
        NativeRuntime::instance().releaseComponentInstance(instanceKey);
    }

    void Api::setOutboundHandler(const std::string& key, std::string codec, std::unique_ptr<OutboundHandler> handler) {
        NativeRuntime::instance().setOutboundHandler(key, std::move(codec), std::move(handler));
    }

    bool Api::setComponentHostHandler(const std::string& key, std::unique_ptr<ComponentHostHandler> handler) {
        return NativeRuntime::instance().setComponentHostHandler(key, std::move(handler));
    }
} // namespace wasmline
