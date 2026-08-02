/**
 * Adapts a failed Wasmline result to exception-based APIs.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
package crow.wasmline.invocation

class WasmlineInvocationException(val error: WasmlineCallError) : IllegalStateException(error.message)
