@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineEndpoint
import crow.wasmline.internal.bridge.WasmlineGeneratedBridge
import crow.wasmline.invocation.WasmlineCallResult
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
    require(descriptor.invocationProtocol == WasmlineInvocationProtocol.WASMLINE_SERVICE) {
        "Generated Wasmline services require WASMLINE_SERVICE, not ${descriptor.invocationProtocol}."
    }
    if (hostServiceRegistry.registerAll(bridge)) setOutbound(hostServiceRegistry.dispatcher)
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
