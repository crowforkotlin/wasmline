@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.spi.WasmlineBindingScope
import crow.wasmline.spi.WasmlineEndpoint
import crow.wasmline.spi.WasmlineHostDispatcher
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass

@PublishedApi
internal fun Wasmline.asEndpoint(): WasmlineEndpoint {
    return object : WasmlineEndpoint {
        override fun invoke(action: String, payload: ByteArray): ByteArray = runBlocking { call(action, payload) }
    }
}

inline fun <reified T : WasmlineService> Wasmline.link(): T {
    return asEndpoint().linkInternal(T::class)
}

@PublishedApi
internal suspend fun Wasmline.bindServicesInternal(block: WasmlineBindingScope.() -> Unit) {
    val scope = WasmlineBindingScope().apply(block)
    setOutbound(scope.toHostDispatcher())
}

/** Bind a local implementation using an explicit service contract. */
suspend fun <T : WasmlineService> Wasmline.bind(contract: KClass<T>, implementation: T) {
    bindServicesInternal {
        bindInternal(contract, implementation)
    }
}

/**
 * Bind a local implementation to its uniquely matching registered service contract.
 *
 * This is the preferred convenience overload for most application code.
 */
suspend fun Wasmline.bind(implementation: WasmlineService) {
    bindServicesInternal {
        bindInternal(implementation)
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


