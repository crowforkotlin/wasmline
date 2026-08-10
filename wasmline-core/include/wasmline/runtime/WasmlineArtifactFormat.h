/**
 * Defines physical Wasmline artifact formats shared by native loading layers.
 *
 * Date: 2026-08-07
 */

#pragma once

#include <cstdint>

namespace wasmline {
    /** Identifies the existing physical artifact formats accepted by native bridges. */
    enum class WasmlineArtifactFormat : int32_t {
        RAW_WASM = 1,
        CWASM = 2,
        PWASM = 3,
    };
} // namespace wasmline
