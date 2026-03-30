// WITH_STDLIB

package test.box

import crow.wasmline.WasmlineService
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.jvm.functions.Function1
import kotlin.jvm.functions.Function2

interface HeartbeatService : WasmlineService {
    fun ping()
}

private class HeartbeatServiceImpl(
    private val sink: MutableList<String>,
) : HeartbeatService {
    override fun ping() {
        sink += "ping"
    }
}

fun box(): String {
    if (runCatching { Class.forName("test.box.HeartbeatService_WasmlineDefinition") }.isSuccess) {
        return "Fail legacyDefinitionStillExists"
    }

    val bridgeClass = Class.forName("test.box.HeartbeatService_WasmlineBridge")
    if (!HeartbeatService::class.java.isAssignableFrom(bridgeClass)) {
        return "Fail bridgeContractType=${bridgeClass.name}"
    }

    val constructors = bridgeClass.declaredConstructors.sortedBy { it.parameterTypes.singleOrNull()?.name.orEmpty() }
    val endpointCtor = constructors.firstOrNull { ctor -> ctor.parameterTypes.singleOrNull()?.name == "crow.wasmline.internal.bridge.WasmlineEndpoint" }
        ?: return "Fail missingEndpointCtor"
    val implementationCtor = constructors.firstOrNull { ctor -> ctor.parameterTypes.singleOrNull() == HeartbeatService::class.java }
        ?: return "Fail missingImplementationCtor"

    val endpointType = endpointCtor.parameterTypes.single()
    var linkedAction: String? = null
    var linkedPayloadSize = -1
    val linkedEndpoint = Proxy.newProxyInstance(
        endpointType.classLoader,
        arrayOf(endpointType),
        InvocationHandler { _, method, args ->
            when (method.name) {
                "invoke" -> {
                    linkedAction = args!![0] as String
                    linkedPayloadSize = (args[1] as ByteArray).size
                    byteArrayOf()
                }

                else -> error("Unexpected endpoint method ${method.name}")
            }
        },
    )

    val proxy = endpointCtor.newInstance(linkedEndpoint) as HeartbeatService
    proxy.ping()
    if (linkedAction != "test.box.HeartbeatService#ping") {
        return "Fail linkedAction=$linkedAction"
    }
    if (linkedPayloadSize != 0) {
        return "Fail linkedPayloadSize=$linkedPayloadSize"
    }

    val bindMethod = bridgeClass.methods.singleOrNull { it.name == "bind" && it.parameterCount == 1 }
        ?: return "Fail missingBindMethod"
    val events = mutableListOf<String>()
    val boundHandlers = linkedMapOf<String, Function1<ByteArray, ByteArray>>()
    val registerAction = object : Function2<String, Function1<ByteArray, ByteArray>, Unit> {
        override fun invoke(action: String, handler: Function1<ByteArray, ByteArray>) {
            val previous = boundHandlers.put(action, handler)
            if (previous != null) error("Duplicate action $action")
        }
    }
    val binderBridge = implementationCtor.newInstance(HeartbeatServiceImpl(events))
    bindMethod.invoke(binderBridge, registerAction)

    val handler = boundHandlers["test.box.HeartbeatService#ping"]
        ?: return "Fail missingHandler"
    val result = handler.invoke(byteArrayOf())
    if (result.isNotEmpty()) {
        return "Fail resultPayloadSize=${result.size}"
    }
    if (events != listOf("ping")) {
        return "Fail events=$events"
    }

    return "OK"
}

