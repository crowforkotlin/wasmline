package crow.wasmline

import kotlinx.serialization.Serializable

/** Identifies an existing Wasmline artifact's physical binary format. */
@Serializable
enum class WasmlineArtifactFormat {
    RAW_WASM,
    CWASM,
    PWASM,
}

/** Returns the stable physical-format code used only by native bridge ABIs. */
internal fun WasmlineArtifactFormat.nativeBridgeCode(): Int = when (this) {
    WasmlineArtifactFormat.RAW_WASM -> 1
    WasmlineArtifactFormat.CWASM -> 2
    WasmlineArtifactFormat.PWASM -> 3
}
