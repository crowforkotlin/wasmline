package crow.wasmline

import kotlinx.serialization.Serializable

/** Identifies an existing Wasmline artifact's physical binary format. */
@Serializable
enum class WasmlineArtifactFormat {
    RAW_WASM,
    CWASM,
    PWASM,
}
