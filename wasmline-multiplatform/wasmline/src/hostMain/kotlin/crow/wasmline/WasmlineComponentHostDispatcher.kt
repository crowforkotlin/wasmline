/**
 * Dispatches typed Component Model host imports to an immutable registry.
 *
 * Date: 2026-08-07
 * Author: crowforkotlin
 */
package crow.wasmline

import crow.wasmline.invocation.WasmlineCallError
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineInvocationException

/**
 * JVM-callable typed Component host dispatcher.
 *
 * Native bridges pass exact Component identifiers separately and exchange only
 * typed-value frames with this object. A null return is reserved for a missing
 * adapter so native code can preserve the canonical missing-adapter error.
 */
internal class WasmlineComponentHostDispatcher(private val registry: WasmlineComponentHostRegistry) {
    @Suppress("unused")
    fun dispatch(interfaceName: String, functionName: String, arguments: ByteArray): ByteArray? {
        val functionId = try {
            WasmlineComponentFunctionId.of(WasmlineComponentInterfaceId.of(interfaceName), functionName)
        } catch (error: IllegalArgumentException) {
            return encode(
                WasmlineCallResult.Failure(
                    WasmlineCallError(
                        code = WasmlineErrorCode.HANDLER_FAILED,
                        message = error.message ?: "Component host identifiers are invalid.",
                    ),
                ),
            )
        }
        val adapter = registry.lookup(functionId) ?: return null
        val result = when (val decoded = WasmlineTypedInvocationCodec.decodeComponentArguments(arguments)) {
            is WasmlineCallResult.Failure -> decoded
            is WasmlineCallResult.Success -> invokeAdapter(adapter, decoded.value)
        }
        return encode(result)
    }

    private fun invokeAdapter(
        adapter: WasmlineComponentHostAdapter,
        arguments: List<WasmlineComponentValue>,
    ): WasmlineCallResult<List<WasmlineComponentValue>> = try {
        adapter.invoke(arguments)
    } catch (error: Exception) {
        WasmlineCallResult.Failure(
            WasmlineCallError(
                code = WasmlineErrorCode.HANDLER_FAILED,
                message = error.message ?: "Typed Component host adapter failed.",
            ),
        )
    }

    private fun encode(result: WasmlineCallResult<List<WasmlineComponentValue>>): ByteArray =
        when (val encoded = WasmlineTypedInvocationCodec.encodeComponentResult(result)) {
            is WasmlineCallResult.Success -> encoded.value
            is WasmlineCallResult.Failure -> throw WasmlineInvocationException(encoded.error)
        }
}
