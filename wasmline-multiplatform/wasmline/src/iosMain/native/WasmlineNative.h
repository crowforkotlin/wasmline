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

#ifdef __cplusplus
extern "C" {
#endif

/** Initializes the global engine with the default backend. */
void wasmline_init_engine();

/** Initializes the global engine for the selected backend. */
void wasmline_warmup_engine(bool usePulley);

/** Releases the global engine and cached runtime state. */
void wasmline_release_engine();

/** Loads a Core Wasm artifact. */
bool wasmline_load_module(const char* key, const char* path, bool isUnsafe);

/** Loads a Component Model artifact. */
bool wasmline_load_component(const char* key, const char* path, bool isUnsafe);

/** Releases a previously loaded artifact. */
void wasmline_release_module(const char* key);

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

#ifdef __cplusplus
}
#endif

#endif
