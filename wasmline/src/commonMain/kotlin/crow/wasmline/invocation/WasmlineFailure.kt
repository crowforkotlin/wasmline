package crow.wasmline.invocation

/**
 * Describes a structured, non-throwing Wasmline operation failure.
 *
 * Callers must branch on [code] rather than parsing [message] or [details].
 * [rawCode] preserves a code that is newer than the local [WasmlineErrorCode]
 * enum and therefore maps to [WasmlineErrorCode.UNKNOWN].
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 *
 * @property code Stable error category understood by this runtime version.
 * @property message Human-readable diagnostic message.
 * @property details Optional backend-specific diagnostic bytes.
 * @property rawCode Original numeric error code received across a bridge.
 */
data class WasmlineFailure(val code: WasmlineErrorCode, val message: String, val details: ByteArray? = null, val rawCode: Int = code.value)
