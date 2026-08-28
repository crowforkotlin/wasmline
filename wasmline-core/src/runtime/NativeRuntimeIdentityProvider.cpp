/**
 * Implements immutable native runtime identity assembly.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */

#include "wasmline/internal/runtime/NativeRuntimeIdentityProvider.h"
#include "wasmline/internal/runtime/NativeBuildIdentity.h"

#include <cstdint>

#include <wasmtime.h>

namespace wasmline {
    namespace {
        constexpr const char* operatingSystem() {
#if defined(__ANDROID__)
            return "android";
#elif defined(__ENVIRONMENT_IPHONE_OS_VERSION_MIN_REQUIRED__) || defined(__ENVIRONMENT_TV_OS_VERSION_MIN_REQUIRED__)
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

        constexpr const char* architecture() {
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

        constexpr uint32_t supportedArtifactFormats() {
            uint32_t formats = 0;
#ifdef WASMTIME_FEATURE_CRANELIFT
            formats |= WASMLINE_ARTIFACT_FORMAT_CAPABILITY_CWASM;
#endif
#ifdef WASMTIME_FEATURE_PULLEY
            formats |= WASMLINE_ARTIFACT_FORMAT_CAPABILITY_PWASM;
#endif
            return formats;
        }

        constexpr int32_t backend() {
#ifdef WASMTIME_FEATURE_CRANELIFT
            return WASMLINE_NATIVE_BACKEND_CRANELIFT;
#else
            return WASMLINE_NATIVE_BACKEND_PULLEY;
#endif
        }

        constexpr const char* craneliftProfileId() {
#ifdef WASMTIME_FEATURE_CRANELIFT
            return WASMLINE_CRANELIFT_AOT_COMPATIBILITY_PROFILE_ID;
#else
            return "";
#endif
        }

        constexpr const char* pulleyProfileId() {
#ifdef WASMTIME_FEATURE_PULLEY
            return WASMLINE_PULLEY_AOT_COMPATIBILITY_PROFILE_ID;
#else
            return "";
#endif
        }

        constexpr const char* cpuFeatureProfiles() {
#ifdef WASMTIME_FEATURE_CRANELIFT
            return "baseline-v1";
#else
            return "";
#endif
        }
    } // namespace

    const WasmlineNativeRuntimeIdentity& NativeRuntimeIdentityProvider::identity() {
        static const WasmlineNativeRuntimeIdentity value = {
            backend(),
            supportedArtifactFormats(),
            WASMTIME_VERSION,
            craneliftProfileId(),
            pulleyProfileId(),
            WASMLINE_NATIVE_BRIDGE_ABI_VERSION,
            WASMLINE_RELEASE_VERSION,
            operatingSystem(),
            architecture(),
            static_cast<int32_t>(sizeof(void*) * 8u),
            cpuFeatureProfiles(),
        };
        return value;
    }
} // namespace wasmline
