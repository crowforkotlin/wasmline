@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineEndpoint
import crow.wasmline.internal.bridge.WasmlineGeneratedBridge
import crow.wasmline.internal.protocol.WasmlineResponseCodec
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.serialization.WasmlineSerializationFactory
import kotlin.reflect.KClass

internal fun Wasmline.callResult(action: String, payload: ByteArray = ByteArray(0)): WasmlineCallResult<ByteArray> =
    WasmlineResponseCodec.decodeLegacyCompatible(call(action, payload))

@PublishedApi
internal class GeneratedWasmlineHostEndpoint(private val wasmline: Wasmline) : WasmlineEndpoint {
    override fun invoke(action: String, payload: ByteArray): ByteArray = invokeResult(action, payload).throwOnFailure()

    override fun invokeResult(action: String, payload: ByteArray): WasmlineCallResult<ByteArray> = wasmline.callResult(action, payload)
}

@PublishedApi
internal fun Wasmline.generatedSerializationFactory(): WasmlineSerializationFactory = serializationFactory

@PublishedApi
internal fun Wasmline.bindGenerated(bridge: WasmlineGeneratedBridge) {
    val additions = linkedMapOf<String, Callback>()
    bridge.bind { action, handler ->
        check(action !in additions) { "Generated service bridge declares duplicate action '$action'." }
        additions[action] = Callback { params -> handler(params ?: ByteArray(0)) }
    }
    WasmlineRouter.registerAll(additions)
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
