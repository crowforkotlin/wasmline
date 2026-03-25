@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.spi.WasmlineBindingScope
import crow.wasmline.spi.WasmlineEndpoint
import kotlin.reflect.KClass

object WasmlineHostEndpoint : WasmlineEndpoint {
    override fun invoke(action: String, payload: ByteArray): ByteArray {
        return WasmBridge.callHost(action, payload)
    }
}

inline fun <reified T : WasmlineService> linkHost(): T {
    return WasmlineHostEndpoint.linkInternal(T::class)
}

@PublishedApi
internal fun bindServicesInternal(block: WasmlineBindingScope.() -> Unit) {
    val scope = WasmlineBindingScope().apply(block)
    for ((action, handler) in scope.snapshot()) {
        WasmRouter.register(action) { payload ->
            handler.handle(payload ?: ByteArray(0))
        }
    }
}

fun <T : WasmlineService> bind(contract: KClass<T>, implementation: T) {
    bindServicesInternal {
        bindInternal(contract, implementation)
    }
}

fun bind(implementation: WasmlineService) {
    bindServicesInternal {
        bindInternal(implementation)
    }
}

inline fun <reified T : WasmlineService> bindAs(implementation: WasmlineService) {
    check(T::class.isInstance(implementation)) {
        "Implementation ${implementation::class.qualifiedName} is not an instance of service contract ${T::class.qualifiedName}."
    }
    bind(T::class, implementation as T)
}

