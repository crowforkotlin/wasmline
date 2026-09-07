package crow.wasmline.loader.internal

import crow.wasmline.WasmlineLoadFailure
import crow.wasmline.WasmlineLoadStage
import crow.wasmline.WasmlineLoadState
import crow.wasmline.invocation.WasmlineErrorCode

/** Creates a terminal loader state with a canonical structured failure. */
internal fun structuredLoadFailure(
    stage: WasmlineLoadStage,
    code: WasmlineErrorCode,
    message: String,
    details: ByteArray? = null,
): WasmlineLoadState.Failure = WasmlineLoadState.Failure(
    code = WasmlineLoadState.CODE_FAILURE,
    failure = WasmlineLoadFailure(
        stage = stage,
        code = code,
        message = message,
        details = details,
    ),
)

/** Creates a terminal source-resolution result with a structured failure. */
internal fun structuredResolutionFailure(
    stage: WasmlineLoadStage,
    code: WasmlineErrorCode,
    message: String,
    details: ByteArray? = null,
): crow.wasmline.loader.WasmlineSourceResolution.Complete = crow.wasmline.loader.WasmlineSourceResolution.Complete(
    structuredLoadFailure(stage, code, message, details),
)
