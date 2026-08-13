package crow.wasmline.invocation

/**
 * Adapts a failed Wasmline result to exception-based APIs.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
class WasmlineInvocationException(val error: WasmlineCallError) : IllegalStateException(error.message)
