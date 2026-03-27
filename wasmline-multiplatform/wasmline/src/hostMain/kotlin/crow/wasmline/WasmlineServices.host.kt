@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineBindingScope
import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass

@PublishedApi
internal fun Wasmline.invokeActionBlocking(action: String, payload: ByteArray): ByteArray {
    return runBlocking { call(action, payload) }
}

inline fun <reified T : WasmlineService> Wasmline.link(): T {
    return linkInternal(T::class) { action, payload ->
        invokeActionBlocking(action, payload)
    }
}

@PublishedApi
internal suspend fun Wasmline.bindServicesInternal(block: WasmlineBindingScope.() -> Unit) {
    val scope = WasmlineBindingScope().apply(block)
    setOutbound(scope.toHostDispatcher())
}

/** Bind a local implementation using an explicit service contract. */
suspend fun <T : WasmlineService> Wasmline.bind(contract: KClass<T>, implementation: T) {
    bindServicesInternal {
        bindInternal(contract, implementation) { action, handler ->
            bind(action, handler)
        }
    }
}

/**
 * Bind a local implementation to its uniquely matching registered service contract.
 *
 * This is the preferred convenience overload for most application code.
 */
suspend fun Wasmline.bind(implementation: WasmlineService) {
    bindServicesInternal {
        bindInternal(implementation) { action, handler ->
            bind(action, handler)
        }
    }
}

/** Bind a local implementation as the explicitly selected service contract. */
suspend inline fun <reified T : WasmlineService> Wasmline.bindAs(implementation: WasmlineService) {
    check(T::class.isInstance(implementation)) {
        "Implementation ${implementation::class.qualifiedName} is not an instance of service contract ${T::class.qualifiedName}."
    }
    bind(T::class, implementation as T)
}

private fun WasmlineBindingScope.toHostDispatcher(): WasmlineHostDispatcher {
    return WasmlineHostDispatcher { action, payload -> invoke(action, payload) }
}


