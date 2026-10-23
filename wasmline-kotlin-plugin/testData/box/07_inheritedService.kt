// WITH_STDLIB

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package test.ir.inherited

import crow.wasmline.WasmlineService
import crow.wasmline.internal.bridge.WasmlineEndpoint
import crow.wasmline.internal.bridge.WasmlineGeneratedBridge
import crow.wasmline.serialization.WasmlineProtobufSerializationFactory
import crow.wasmline.serialization.WasmlineSerializationFactory

interface ParentService : WasmlineService {
    fun inherited(value: String): String
    fun add(left: Int, right: Int): Int
}

interface LeftService : ParentService
interface RightService : ParentService

interface ChildService :
    LeftService,
    RightService {
    fun local(): String
}

private class ChildImplementation : ChildService {
    override fun inherited(value: String): String = "parent:$value"
    override fun add(left: Int, right: Int): Int = left + right
    override fun local(): String = "child"
}

fun box(): String {
    val actions = mutableMapOf<String, (ByteArray) -> ByteArray>()
    val endpoint = object : WasmlineEndpoint {
        override fun invoke(action: String, payload: ByteArray): ByteArray =
            requireNotNull(actions[action]) { "Unbound action: $action" }(payload)
    }
    val bridgeClass = Class.forName("test.ir.inherited.ChildService_WasmlineBridge")
    val constructor = bridgeClass.getConstructor(
        WasmlineEndpoint::class.java,
        ChildService::class.java,
        WasmlineSerializationFactory::class.java,
    )
    val binder = constructor.newInstance(
        endpoint,
        ChildImplementation(),
        WasmlineProtobufSerializationFactory,
    ) as WasmlineGeneratedBridge
    binder.bind { action, handler -> actions[action] = handler }
    val proxy = constructor.newInstance(
        endpoint,
        null,
        WasmlineProtobufSerializationFactory,
    ) as ChildService

    if (proxy.inherited("payload") != "parent:payload") return "Inherited method returned an incorrect result"
    if (proxy.add(19, 23) != 42) return "Inherited multi-parameter method returned an incorrect result"
    if (proxy.local() != "child") return "Declared method returned an incorrect result"
    return "OK"
}
