package crow.wasmline.invocation

/**
 * Adapts a structured [WasmlineFailure] to an exception-based API.
 *
 * Normal Wasmline control flow returns [WasmlineCallResult]. This exception is
 * created only by explicit throwing adapters such as `getOrThrow()`.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 *
 * @property failure Structured failure that caused this exception.
 * @param cause Optional platform exception retained only for diagnostics.
 */
open class WasmlineException(val failure: WasmlineFailure, cause: Throwable? = null) : RuntimeException(failure.message, cause)
