@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineEndpoint
import crow.wasmline.internal.bridge.WasmlineGeneratedBridge
import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass

@PublishedApi
internal fun Wasmline.invokeActionBlocking(action: String, payload: ByteArray): ByteArray {
    return runBlocking { call(action, payload) }
}

@Deprecated("Wasmline compiler internal API", level = DeprecationLevel.HIDDEN)
class GeneratedWasmlineHostEndpoint(
    private val wasmline: Wasmline,
) : WasmlineEndpoint {
    override fun invoke(action: String, payload: ByteArray): ByteArray {
        return wasmline.invokeActionBlocking(action, payload)
    }
}

@Deprecated("Wasmline compiler internal API", level = DeprecationLevel.HIDDEN)
suspend fun Wasmline.bindGenerated(bridge: WasmlineGeneratedBridge) {
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

/** Bind a local implementation using an explicit service contract. */
suspend fun <T : WasmlineService> Wasmline.bind(contract: KClass<T>, implementation: T) {
    error("Wasmline compiler plugin is not applied or failed to replace Wasmline.bind(contract, implementation).")
}

/**
 * Bind a local implementation to its uniquely matching registered service contract.
 *
 * This is the preferred convenience overload for most application code.
 */
suspend fun Wasmline.bind(implementation: WasmlineService) {
    error("Wasmline compiler plugin is not applied or failed to replace Wasmline.bind(implementation).")
}

/** Bind a local implementation as the explicitly selected service contract. */
suspend fun <T : WasmlineService> Wasmline.bindAs(implementation: WasmlineService) {
    error("Wasmline compiler plugin is not applied or failed to replace Wasmline.bindAs<T>().")
}

private fun Map<String, (ByteArray) -> ByteArray>.toHostDispatcher(): WasmlineHostDispatcher {
    return WasmlineHostDispatcher { action, payload ->
        val handler = this[action] ?: error("No Wasmline action bound for '$action'.")
        handler(payload)
    }
}


