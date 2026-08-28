/**
 * Defines internal native runtime identity assembly.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */

#pragma once

#include "wasmline/api/NativeRuntimeIdentity.h"

namespace wasmline {
    /**
     * Builds and caches native runtime identity from immutable engine build inputs.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    class NativeRuntimeIdentityProvider final {
    public:
        /** Returns the cached identity. */
        static const WasmlineNativeRuntimeIdentity& identity();

    private:
        NativeRuntimeIdentityProvider() = delete;
    };
} // namespace wasmline
