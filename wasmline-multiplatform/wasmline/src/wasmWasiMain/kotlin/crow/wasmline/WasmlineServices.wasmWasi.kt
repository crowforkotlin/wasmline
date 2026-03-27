@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineBindingScope
import crow.wasmline.internal.bridge.WasmlineEndpoint
import kotlin.reflect.KClass

object WasmlineHostEndpoint : WasmlineEndpoint {
    override fun invoke(action: String, payload: ByteArray): ByteArray {
        return WasmBridge.callHost(action, payload)
    }
}

internal inline fun <reified T : WasmlineService> linkHost(): T {
    return linkInternal(T::class) { action, payload ->
        WasmBridge.callHost(action, payload)
    }
}

@PublishedApi
internal fun bindServicesInternal(block: WasmlineBindingScope.() -> Unit) {
    val scope = WasmlineBindingScope().apply(block)
    for ((action, handler) in scope.snapshot()) {
        WasmRouter.register(action) { payload ->
            handler(payload ?: ByteArray(0))
        }
    }
}

fun <T : WasmlineService> bind(contract: KClass<T>, implementation: T) {
    bindServicesInternal {
        bindInternal(contract, implementation) { action, handler ->
            bind(action, handler)
        }
    }
}

fun bind(implementation: WasmlineService) {
    bindServicesInternal {
        bindInternal(implementation) { action, handler ->
            bind(action, handler)
        }
    }
}

inline fun <reified T : WasmlineService> bindAs(implementation: WasmlineService) {
    check(T::class.isInstance(implementation)) {
        "Implementation ${implementation::class.qualifiedName} is not an instance of service contract ${T::class.qualifiedName}."
    }
    bind(T::class, implementation as T)
}

