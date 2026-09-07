package crow.wasmline

import kotlinx.serialization.Serializable

/**
 * Identifies the binary execution model used by an artifact.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
@Serializable
enum class WasmlineExecutionModel {
    CORE_WASM,
    COMPONENT_MODEL,
}
