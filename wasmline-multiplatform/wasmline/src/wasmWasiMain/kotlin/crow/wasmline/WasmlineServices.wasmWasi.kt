@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineBindingScope
import crow.wasmline.internal.bridge.WasmlineGeneratedBridge
import kotlin.reflect.KClass



@PublishedApi
internal fun bindServicesInternal(block: WasmlineBindingScope.() -> Unit) {
    val scope = WasmlineBindingScope().apply(block)
    for ((action, handler) in scope.snapshot()) {
        WasmRouter.register(action) { payload ->
            handler(payload ?: ByteArray(0))
        }
    }
}

fun bindGenerated(bridge: WasmlineGeneratedBridge) {
    bindServicesInternal {
        bridge.bind { action, handler ->
            bind(action, handler)
        }
    }
}

fun <T : WasmlineService> bind(contract: KClass<T>, implementation: T) {
    error("Wasmline compiler plugin is not applied or failed to replace bind(contract, implementation).")
}

fun bind(implementation: WasmlineService) {
    error("Wasmline compiler plugin is not applied or failed to replace bind(implementation).")
}

fun <T : WasmlineService> bindAs(implementation: WasmlineService) {
    error("Wasmline compiler plugin is not applied or failed to replace bindAs<T>().")
}

