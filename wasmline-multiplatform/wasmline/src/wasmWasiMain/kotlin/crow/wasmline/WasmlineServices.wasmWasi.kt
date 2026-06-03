@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineGeneratedBridge
import crow.wasmline.serialization.WasmlineSerializationFactory
import kotlin.reflect.KClass

@PublishedApi
internal fun Wasmline.generatedSerializationFactory(): WasmlineSerializationFactory = serializationFactory

@PublishedApi
internal fun currentGeneratedSerializationFactory(): WasmlineSerializationFactory = Wasmline.get().serializationFactory

@PublishedApi
internal fun Wasmline.bindGenerated(bridge: WasmlineGeneratedBridge) {
    val registeredActions = linkedSetOf<String>()
    bridge.bind { action, handler ->
        check(registeredActions.add(action)) { "Action '$action' is already bound in this Wasmline binding scope." }
        WasmlineRouter.register(action) { payload ->
            handler(payload ?: ByteArray(0))
        }
    }
}

@PublishedApi
internal fun bindGenerated(bridge: WasmlineGeneratedBridge) {
    Wasmline.get().bindGenerated(bridge)
}

fun <T : WasmlineService> Wasmline.link(): T {
    error("Wasmline compiler plugin is not applied or failed to replace Wasmline.link<T>().")
}

fun <T : WasmlineService> Wasmline.bind(contract: KClass<T>, implementation: T) {
    error("Wasmline compiler plugin is not applied or failed to replace Wasmline.bind(contract, implementation).")
}

fun Wasmline.bind(implementation: WasmlineService) {
    error("Wasmline compiler plugin is not applied or failed to replace Wasmline.bind(implementation).")
}

fun <T : WasmlineService> bind(contract: KClass<T>, implementation: T) {
    Wasmline.get().bind(contract, implementation)
}

fun bind(implementation: WasmlineService) {
    Wasmline.get().bind(implementation)
}
