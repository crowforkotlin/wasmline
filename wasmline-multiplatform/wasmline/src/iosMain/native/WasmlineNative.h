/**
 * Defines the iOS C bridge for the Wasmline native API.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
#ifndef WASMLINE_NATIVE_H
#define WASMLINE_NATIVE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Initializes the selected engine without invalidating loaded artifacts. */
bool wasmline_warmup_engine(bool usePulley);

/** Releases the global engine and cached runtime state. */
void wasmline_release_engine();

/** Returns the exact linked Wasmtime version. */
const char* wasmline_wasmtime_version();

/** Returns whether the linked runtime supports Cranelift artifacts. */
bool wasmline_supports_cranelift();

/** Returns whether the linked runtime supports Pulley artifacts. */
bool wasmline_supports_pulley();

/** Loads a Core Wasm artifact with an explicit physical artifact format. */
bool wasmline_load_module_with_format(const char* key, const char* path, int32_t formatCode, bool isUnsafe);

/** Loads a Component Model artifact with an explicit physical artifact format. */
bool wasmline_load_component_with_format(const char* key, const char* path, int32_t formatCode, bool isUnsafe);

/** Releases a previously loaded artifact. */
void wasmline_release_module(const char* key);

/** Releases one explicitly instantiated Component session. */
void wasmline_release_component_instance(const char* instanceKey);

/** Drops one owned Component resource associated with an instance. */
bool wasmline_drop_component_resource(const char* instanceKey,
                                     const void* data,
                                     size_t dataLen);

/** Creates one owned imported Host resource and returns its typed carrier. */
char* wasmline_create_component_host_resource(const char* instanceKey,
                                              const char* interfaceId,
                                              const char* resourceName,
                                              uint32_t representation,
                                              size_t* outLen);

/** Invokes the Core Wasmline entry point. */
char* wasmline_invoke_inbound(const char* key,
                              const char* action, size_t actionLen,
                              const void* data,
                              size_t dataLen,
                              size_t* outLen);

/** Invokes a raw Core Wasm export with typed values. */
char* wasmline_invoke_raw(const char* key,
                           const char* exportName,
                           size_t exportNameLen,
                           const void* data,
                           size_t dataLen,
                           size_t* outLen);

/** Invokes a Component Model export with typed values. */
char* wasmline_invoke_component(const char* key,
                                const char* exportName,
                                size_t exportNameLen,
                                const void* data,
                                size_t dataLen,
                                size_t* outLen);

/** Invokes an export on one explicitly instantiated Component session. */
char* wasmline_invoke_component_instance(const char* instanceKey,
                                         const char* exportName,
                                         size_t exportNameLen,
                                         const void* data,
                                         size_t dataLen,
                                         size_t* outLen);

/** Releases memory returned by the native bridge. */
void wasmline_free_memory(char* ptr);

/** Defines the callback signature for outbound host calls. */
typedef char* (*OutboundCallback)(const char* key,
                                  size_t keyLen,
                                  const char* action,
                                  size_t actionLen,
                                  const char* payload,
                                  size_t payloadLen,
                                  size_t* outLen);

/** Registers the outbound callback for an artifact. */
void wasmline_set_outbound_handler(const char* key, const char* codec, OutboundCallback callback);

/** Defines the callback signature for typed Component host imports. */
typedef char* (*ComponentHostCallback)(const char* key,
                                       size_t keyLen,
                                       const char* interfaceName,
                                       size_t interfaceNameLen,
                                       const char* functionName,
                                       size_t functionNameLen,
                                       const char* arguments,
                                       size_t argumentsLen,
                                       size_t* outLen);

/** Registers the typed Component host callback for an artifact. */
bool wasmline_set_component_host_handler(const char* key, ComponentHostCallback callback);

/** Creates an isolated Component session with its host callback installed before instantiation. */
bool wasmline_instantiate_component(const char* artifactKey,
                                    const char* instanceKey,
                                    ComponentHostCallback callback);

#ifdef __cplusplus
}
#endif

#endif
