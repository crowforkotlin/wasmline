// WITH_STDLIB

package test.box

import crow.wasmline.WasmlineService
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.jvm.functions.Function1
import kotlin.jvm.functions.Function2

interface EchoService : WasmlineService {
    fun echo(payload: ByteArray): ByteArray
}

private class EchoServiceImpl : EchoService {
    override fun echo(payload: ByteArray): ByteArray = payload
}

fun box(): String {
    if (runCatching { Class.forName("test.box.EchoService_WasmlineDefinition") }.isSuccess) {
        return "Fail legacyDefinitionStillExists"
    }

    val bridgeClass = Class.forName("test.box.EchoService_WasmlineBridge")
    if (!EchoService::class.java.isAssignableFrom(bridgeClass)) {
        return "Fail bridgeContractType=${bridgeClass.name}"
    }

    val constructors = bridgeClass.declaredConstructors.sortedBy { it.parameterTypes.singleOrNull()?.name.orEmpty() }
    if (constructors.size != 2) {
        return "Fail constructorCount=${constructors.size}"
    }

    val endpointCtor = constructors.firstOrNull { ctor -> ctor.parameterTypes.singleOrNull()?.name == "crow.wasmline.internal.bridge.WasmlineEndpoint" }
        ?: return "Fail missingEndpointCtor"
    val implementationCtor = constructors.firstOrNull { ctor -> ctor.parameterTypes.singleOrNull() == EchoService::class.java }
        ?: return "Fail missingImplementationCtor"

    val bindMethod = bridgeClass.methods.singleOrNull { it.name == "bind" && it.parameterCount == 1 }
        ?: return "Fail missingBindMethod"
    if (bindMethod.parameterTypes.single().name != "kotlin.jvm.functions.Function2") {
        return "Fail bindRegistrarType=${bindMethod.parameterTypes.single().name}"
    }

    val endpointType = endpointCtor.parameterTypes.single()

    var action: String? = null
    var payload: ByteArray? = null
    val linkedEndpoint = Proxy.newProxyInstance(
        endpointType.classLoader,
        arrayOf(endpointType),
        InvocationHandler { _, method, args ->
            when (method.name) {
                "invoke" -> {
                    action = args!![0] as String
                    payload = (args[1] as ByteArray).copyOf()
                    "reply:${payload!!.decodeToString()}".encodeToByteArray()
                }

                else -> error("Unexpected endpoint method ${method.name}")
            }
        },
    )

    val proxy = endpointCtor.newInstance(linkedEndpoint) as EchoService
    if (proxy::class.qualifiedName != "test.box.EchoService_WasmlineBridge") {
        return "Fail proxyType=${proxy::class.qualifiedName}"
    }

    if (proxy !is EchoService) {
        return "Fail proxyContractType=${proxy::class.qualifiedName}"
    }

    val result = proxy.echo("hello".encodeToByteArray()).decodeToString()
    if (result != "reply:hello") {
        return "Fail result=$result"
    }
    if (action != "test.box.EchoService#echo") {
        return "Fail action=$action"
    }
    if (payload?.decodeToString() != "hello") {
        return "Fail payload=${payload?.decodeToString()}"
    }

    val boundHandlers = linkedMapOf<String, Function1<ByteArray, ByteArray>>()
    val registerAction = object : Function2<String, Function1<ByteArray, ByteArray>, Unit> {
        override fun invoke(registeredAction: String, handler: Function1<ByteArray, ByteArray>) {
            val existing = boundHandlers.put(registeredAction, handler)
            if (existing != null) error("Duplicate action $registeredAction")
        }
    }
    val binderBridge = implementationCtor.newInstance(EchoServiceImpl())
    bindMethod.invoke(binderBridge, registerAction)

    if (boundHandlers.keys != setOf("test.box.EchoService#echo")) {
        return "Fail boundActions=${boundHandlers.keys}"
    }

    val boundEndpoint = Proxy.newProxyInstance(
        endpointType.classLoader,
        arrayOf(endpointType),
        InvocationHandler { _, method, args ->
            when (method.name) {
                "invoke" -> {
                    val invokedAction = args!![0] as String
                    val invokedPayload = args[1] as ByteArray
                    val handler = boundHandlers[invokedAction] ?: error("Missing handler $invokedAction")
                    handler.invoke(invokedPayload)
                }

                else -> error("Unexpected endpoint method ${method.name}")
            }
        },
    )
    val boundProxy = endpointCtor.newInstance(boundEndpoint) as EchoService
    val boundResult = boundProxy.echo("bound".encodeToByteArray()).decodeToString()
    if (boundResult != "bound") {
        return "Fail boundResult=$boundResult"
    }

    return "OK"
}

