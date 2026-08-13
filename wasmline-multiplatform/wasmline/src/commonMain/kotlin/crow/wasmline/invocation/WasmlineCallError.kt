package crow.wasmline.invocation

/**
 * Describes a failed Wasmline invocation.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
data class WasmlineCallError(
    val code: WasmlineErrorCode,
    val message: String,
    val details: ByteArray? = null,
    val rawCode: Int = code.value,
)
