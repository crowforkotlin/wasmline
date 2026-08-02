/**
 * Describes a failed Wasmline invocation.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
package crow.wasmline.invocation

data class WasmlineCallError(
    val code: WasmlineErrorCode,
    val message: String,
    val details: ByteArray? = null,
    val rawCode: Int = code.value,
)
