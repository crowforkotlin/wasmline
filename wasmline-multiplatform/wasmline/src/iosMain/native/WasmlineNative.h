/**
 * WasmlineNative.h
 * C bridge declarations for the iOS Wasmline host runtime.
 */
#ifndef WASMLINE_NATIVE_H
#define WASMLINE_NATIVE_H

#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Initializes the global engine with the default backend.
 */
void wasmline_init_engine();

/**
 * Eagerly initializes the global engine for a specific backend.
 */
void wasmline_warmup_engine(bool usePulley);

/**
 * Releases the global engine and cached runtime state.
 */
void wasmline_release_engine();

/**
 * Loads a prepared local module artifact.
 */
bool wasmline_load_module(const char* key, const char* path, bool isUnsafe);

/**
 * Releases a previously loaded module.
 */
void wasmline_release_module(const char* key);

/**
 * Invokes the inbound entrypoint of a loaded module.
 */
char* wasmline_invoke_inbound(const char* key,
                              const char* action, size_t actionLen,
                              const void* data,
                              size_t dataLen,
                              size_t* outLen);

/**
 * Releases memory returned by the native bridge.
 */
void wasmline_free_memory(char* ptr);

/**
 * Native callback signature used for outbound host calls.
 */
typedef char* (*OutboundCallback)(const char* action, size_t actionLen, const char* payload, size_t payloadLen);

/**
 * Registers the outbound callback for a loaded module.
 */
void wasmline_set_outbound_handler(const char* key, OutboundCallback callback);

#ifdef __cplusplus
}
#endif

#endif