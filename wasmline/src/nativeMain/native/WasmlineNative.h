/**
 * Defines the portable Kotlin/Native C bridge for the Wasmline native API.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
#ifndef WASMLINE_NATIVE_H
#define WASMLINE_NATIVE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "wasmline/api/NativeRuntimeIdentity.h"

#ifdef __cplusplus
extern "C" {
#endif

/** Initializes the selected engine without invalidating loaded artifacts. */
bool wasmline_warmup_engine(bool usePulley);

/** Releases the global engine and cached runtime state. */
void wasmline_release_engine();

/** Forces the selected Native engine archive into the final link. */
void wasmline_native_engine_link_anchor();

/** Returns whether a host filesystem path exists. */
bool wasmline_path_exists(const char* path);

/** Enters the process-wide bridge lock. */
void wasmline_lock();

/** Leaves the process-wide bridge lock. */
void wasmline_unlock();

/** Loads a Core Wasm artifact and returns a WLMF result carrier. */
char* wasmline_load_module_with_format(const char* key,
                                       const char* path,
                                       int32_t formatCode,
                                       bool isUnsafe,
                                       size_t* outLen);

/** Loads a Component Model artifact and returns a WLMF result carrier. */
char* wasmline_load_component_with_format(const char* key,
                                          const char* path,
                                          int32_t formatCode,
                                          bool isUnsafe,
                                          size_t* outLen);

/** Releases a previously loaded artifact. */
void wasmline_release_module(const char* key);

/** Defines a synchronous Core Wasm import callback. */
typedef char* (*WasmlineRawImportCallback)(void* user,
                                            const char* sessionKey,
                                            const char* module,
                                            size_t moduleLen,
                                            const char* name,
                                            size_t nameLen,
                                            const void* arguments,
                                            size_t argumentsLen,
                                            size_t* outLen);

/** Releases each non-null buffer returned by a raw import callback and is required for registered imports. */
typedef void (*WasmlineRawImportBufferFree)(char* buffer);

/** Releases raw import callback user data. */
typedef void (*WasmlineRawImportUserFinalizer)(void* user);

/** Returns reflected exports for a loaded Core Wasm artifact. */
char* wasmline_core_module_exports(const char* key, size_t* outLen);

/** Creates an isolated Core Wasm RAW_EXPORT session. */
char* wasmline_core_create_session(const char* artifactKey,
                                  const char* sessionKey,
                                  const void* imports,
                                  size_t importsLen,
                                  WasmlineRawImportCallback callback,
                                  WasmlineRawImportBufferFree bufferFree,
                                  void* callbackUser,
                                  WasmlineRawImportUserFinalizer userFinalizer,
                                  const char* memoryExportName,
                                  size_t* outLen);

/** Invokes an export on an isolated Core Wasm RAW_EXPORT session. */
char* wasmline_core_invoke(const char* sessionKey,
                           const char* exportName,
                           size_t exportNameLen,
                           const void* arguments,
                           size_t argumentsLen,
                           size_t* outLen);

/** Releases an isolated Core Wasm RAW_EXPORT session. */
void wasmline_core_release_session(const char* sessionKey);

/** Returns raw session memory size as a typed result carrier. */
char* wasmline_core_memory_size(const char* sessionKey, bool pages, size_t* outLen);

/** Reads raw session memory directly into caller-owned storage and returns only a failure carrier. */
char* wasmline_core_memory_read_into(const char* sessionKey,
                                     uint64_t offset,
                                     void* destination,
                                     uint64_t length,
                                     bool* outSuccess,
                                     size_t* outLen);

/** Writes caller-owned storage directly into raw session memory and returns only a failure carrier. */
char* wasmline_core_memory_write_from(const char* sessionKey,
                                      uint64_t offset,
                                      const void* source,
                                      uint64_t length,
                                      bool* outSuccess,
                                      size_t* outLen);

/** Grows raw session memory and returns the previous page count. */
char* wasmline_core_memory_grow(const char* sessionKey,
                                uint64_t deltaPages,
                                size_t* outLen);

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

/** Allocates callback response memory owned by the native bridge. */
char* wasmline_allocate_memory(size_t size);

/** Releases memory allocated or returned by the native bridge. */
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
