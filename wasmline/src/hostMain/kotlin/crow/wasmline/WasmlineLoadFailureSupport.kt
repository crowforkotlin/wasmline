package crow.wasmline

import crow.wasmline.invocation.WasmlineErrorCode

/** Builds a structured failure for the host-side load pipeline. */
internal fun loadFailure(
    stage: WasmlineLoadStage,
    code: WasmlineErrorCode,
    message: String,
    details: ByteArray? = null,
    rawCode: Int = code.value,
): WasmlineLoadFailure = WasmlineLoadFailure(
    stage = stage,
    code = code,
    message = message,
    details = details,
    rawCode = rawCode,
)

/** Converts a structured load failure to a platform load state. */
internal fun WasmlineLoadFailure.toLoadState(): WasmlineLoadState = WasmlineLoadState.Failure(
    code = WasmlineLoadState.CODE_FAILURE,
    failure = this,
)
