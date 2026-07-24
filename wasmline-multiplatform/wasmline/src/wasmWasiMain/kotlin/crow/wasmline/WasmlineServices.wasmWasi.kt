@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineEndpoint
import crow.wasmline.internal.bridge.WasmlineGeneratedBridge
import crow.wasmline.serialization.WasmlineSerializationFactory
import kotlin.reflect.KClass

@PublishedApi
internal class GeneratedWasmlineHostEndpoint(private val wasmline: Wasmline) : WasmlineEndpoint {
    override fun invoke(action: String, payload: ByteArray): ByteArray = wasmline.call(action, payload)
}

@PublishedApi
internal fun Wasmline.generatedSerializationFactory(): WasmlineSerializationFactory = serializationFactory

@PublishedApi
internal fun Wasmline.bindGenerated(bridge: WasmlineGeneratedBridge) {
    bridge.bind { action, handler ->
        WasmlineRouter.register(action) { params -> handler(params ?: ByteArray(0)) }
    }
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
