// WITH_STDLIB

package test.box

import crow.wasmline.WasmlineService
import crow.wasmline.serialization.WasmlineProtobufSerializationFactory
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.jvm.functions.Function1
import kotlin.jvm.functions.Function2

interface GreetingService : WasmlineService {
    fun greet(name: String, greeting: String): String
    fun log(message: String, level: Int, tag: String)
}

private class GreetingServiceImpl : GreetingService {
    override fun greet(name: String, greeting: String): String = "$greeting, $name!"
    override fun log(message: String, level: Int, tag: String) {}
}

fun box(): String {
    val bridgeClass = Class.forName("test.box.GreetingService_WasmlineBridge")
    if (!GreetingService::class.java.isAssignableFrom(bridgeClass)) {
        return "Fail bridgeContractType=${bridgeClass.name}"
    }

    val constructors = bridgeClass.declaredConstructors
    val endpointCtor = constructors.firstOrNull { ctor ->
        ctor.parameterTypes.size == 3 &&
            ctor.parameterTypes[0].name == "crow.wasmline.internal.bridge.WasmlineEndpoint"
    } ?: return "Fail missingEndpointCtor (found: ${constructors.map { it.parameterTypes.toList() }})"

    val endpointType = endpointCtor.parameterTypes[0]
    val factory = WasmlineProtobufSerializationFactory

    var capturedAction: String? = null
    var capturedPayload: ByteArray? = null
    val linkedEndpoint = Proxy.newProxyInstance(
        endpointType.classLoader,
        arrayOf(endpointType),
        InvocationHandler { _, method, args ->
            when (method.name) {
                "invoke" -> {
                    capturedAction = args!![0] as String
                    capturedPayload = (args[1] as ByteArray).copyOf()
                    "Hello back!".encodeToByteArray()
                }
                else -> error("Unexpected endpoint method ${method.name}")
            }
        },
    )

    val proxy = endpointCtor.newInstance(linkedEndpoint, null, factory) as GreetingService
    if (proxy::class.qualifiedName != "test.box.GreetingService_WasmlineBridge") {
        return "Fail proxyType=${proxy::class.qualifiedName}"
    }

    val greetResult = proxy.greet("Alice", "Hi")
    if (capturedAction != "test.box.GreetingService#greet") {
        return "Fail greetAction=$capturedAction"
    }
    if (capturedPayload == null || capturedPayload!!.isEmpty()) {
        return "Fail emptyGreetPayload"
    }

    proxy.log("test message", 3, "APP")
    if (capturedAction != "test.box.GreetingService#log") {
        return "Fail logAction=$capturedAction"
    }

    val implementationCtor = constructors.firstOrNull { ctor ->
        ctor.parameterTypes.size == 3 &&
            ctor.parameterTypes[0].name.contains("GreetingService")
    } ?: return "Fail missingImplementationCtor"

    val bindMethod = bridgeClass.methods.singleOrNull { it.name == "bind" && it.parameterCount == 1 }
        ?: return "Fail missingBindMethod"

    val boundHandlers = linkedMapOf<String, Function1<ByteArray, ByteArray>>()
    val registerAction = object : Function2<String, Function1<ByteArray, ByteArray>, Unit> {
        override fun invoke(registeredAction: String, handler: Function1<ByteArray, ByteArray>) {
            boundHandlers[registeredAction] = handler
        }
    }
    val binderBridge = implementationCtor.newInstance(null, GreetingServiceImpl(), factory)
    bindMethod.invoke(binderBridge, registerAction)

    val expectedActions = setOf(
        "test.box.GreetingService#greet",
        "test.box.GreetingService#log",
    )
    if (boundHandlers.keys != expectedActions) {
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
    val boundProxy = endpointCtor.newInstance(boundEndpoint, null, factory) as GreetingService
    val boundGreetResult = boundProxy.greet("Bob", "Hello")
    if (boundGreetResult != "Hello, Bob!") {
        return "Fail boundGreetResult=$boundGreetResult"
    }

    boundProxy.log("bound test", 1, "BOUND")

    return "OK"
}
