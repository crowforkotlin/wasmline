@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineEndpoint
import crow.wasmline.internal.bridge.WasmlineGeneratedBridge
import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import crow.wasmline.internal.protocol.WasmlineResponseCodec
import crow.wasmline.invocation.WasmlineCallError
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.serialization.WasmlineSerializationFactory
import crow.wasmline.serialization.WasmlineSerializationRegistry
import kotlin.reflect.KClass

@PublishedApi
internal class GeneratedWasmlineHostEndpoint(private val wasmline: Wasmline) : WasmlineEndpoint {
    override fun invoke(action: String, payload: ByteArray): ByteArray = invokeResult(action, payload).throwOnFailure()

    override fun invokeResult(action: String, payload: ByteArray): WasmlineCallResult<ByteArray> = wasmline.callResult(action, payload)
}

@PublishedApi
internal fun Wasmline.generatedSerializationFactory(): WasmlineSerializationFactory =
    WasmlineSerializationRegistry.requireFactory(config.serialization.factoryId)

@PublishedApi
internal fun Wasmline.bindGenerated(bridge: WasmlineGeneratedBridge) {
    val handlers = linkedMapOf<String, (ByteArray) -> ByteArray>()
    bridge.bind { action, handler ->
        check(action !in handlers) { "Action '$action' is already bound in this Wasmline binding scope." }
        handlers[action] = handler
    }
    setOutbound(handlers.toHostDispatcher())
}

fun <T : WasmlineService> Wasmline.link(): T {
    error("Wasmline compiler plugin is not applied or failed to replace Wasmline.link<T>().")
}

/**
 * Binds a local implementation using an explicit service contract.
 */
fun <T : WasmlineService> Wasmline.bind(contract: KClass<T>, implementation: T) {
    error("Wasmline compiler plugin is not applied or failed to replace Wasmline.bind(contract, implementation).")
}

/**
 * Bind a local implementation to its uniquely matching registered service contract.
 *
 * This is the preferred convenience overload for most application code.
 */
fun Wasmline.bind(implementation: WasmlineService) {
    error("Wasmline compiler plugin is not applied or failed to replace Wasmline.bind(implementation).")
}

private fun Map<String, (ByteArray) -> ByteArray>.toHostDispatcher(): WasmlineHostDispatcher = WasmlineHostDispatcher { action, payload ->
    val handler = this[action]
    if (handler == null) {
        val error = if (isEmpty()) {
            WasmlineCallError(
                code = WasmlineErrorCode.ACTION_NOT_BOUND,
                message = "No Wasmline action is bound.",
            )
        } else {
            WasmlineCallError(
                code = WasmlineErrorCode.UNKNOWN_ACTION,
                message = "Wasmline action is not registered: $action.",
            )
        }
        return@WasmlineHostDispatcher WasmlineResponseCodec.encodeFailure(error)
    }

    return@WasmlineHostDispatcher try {
        handler(payload)
    } catch (error: Throwable) {
        WasmlineResponseCodec.encodeFailure(
            WasmlineCallError(
                code = WasmlineErrorCode.HANDLER_FAILED,
                message = error.message ?: "Wasmline action handler failed.",
            ),
        )
    }
}
