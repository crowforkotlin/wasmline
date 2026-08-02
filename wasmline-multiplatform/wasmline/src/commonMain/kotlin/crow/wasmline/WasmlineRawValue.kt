/**
 * Defines values for direct Core Wasm export calls.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
package crow.wasmline

sealed interface WasmlineRawValue {
    data class I32(val value: Int) : WasmlineRawValue

    data class I64(val value: Long) : WasmlineRawValue

    data class F32(val value: Float) : WasmlineRawValue

    data class F64(val value: Double) : WasmlineRawValue
}
