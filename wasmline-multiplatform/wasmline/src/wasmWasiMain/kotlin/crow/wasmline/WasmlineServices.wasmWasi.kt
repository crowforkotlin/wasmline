@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineGeneratedBridge
import kotlin.reflect.KClass



@Deprecated("Wasmline compiler internal API", level = DeprecationLevel.HIDDEN)
fun bindGenerated(bridge: WasmlineGeneratedBridge) {
    val registeredActions = linkedSetOf<String>()
    bridge.bind { action, handler ->
        check(registeredActions.add(action)) { "Action '$action' is already bound in this Wasmline binding scope." }
        WasmRouter.register(action) { payload ->
            handler(payload ?: ByteArray(0))
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

