/**
 * Defines the stable read-only native runtime identity C ABI.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */

#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Stable native backend codes exposed across C interop boundaries. */
typedef enum WasmlineNativeBackendCode {
    WASMLINE_NATIVE_BACKEND_PULLEY = 1,
    WASMLINE_NATIVE_BACKEND_CRANELIFT = 2,
} WasmlineNativeBackendCode;

/** Stable physical artifact-format capability bits. */
typedef enum WasmlineArtifactFormatCapability {
    WASMLINE_ARTIFACT_FORMAT_CAPABILITY_CWASM = 1u << 0u,
    WASMLINE_ARTIFACT_FORMAT_CAPABILITY_PWASM = 1u << 1u,
} WasmlineArtifactFormatCapability;

/**
 * Contains immutable identity values cached when the native library initializes.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
typedef struct WasmlineNativeRuntimeIdentity {
    int32_t backend;
    uint32_t supported_artifact_formats;
    const char* wasmtime_version;
    const char* cranelift_aot_compatibility_profile_id;
    const char* pulley_aot_compatibility_profile_id;
    int32_t native_bridge_abi_version;
    const char* wasmline_release_version;
    const char* operating_system;
    const char* architecture;
    int32_t pointer_width;
    const char* supported_cpu_feature_profiles;
} WasmlineNativeRuntimeIdentity;

/** Returns the process-wide immutable native runtime identity. */
const WasmlineNativeRuntimeIdentity* wasmline_get_native_runtime_identity(void);

#ifdef __cplusplus
}
#endif
