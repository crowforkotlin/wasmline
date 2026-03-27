// WITH_STDLIB

package test.box

import crow.wasmline.WasmlineService
import kotlin.jvm.functions.Function1
import kotlin.jvm.functions.Function2

interface EchoService : WasmlineService {
    fun echo(payload: ByteArray): ByteArray
}

private class EchoServiceImpl : EchoService {
    override fun echo(payload: ByteArray): ByteArray = payload
}

private fun Any.invokeNoArgMethod(prefix: String): Any? {
    val method = javaClass.methods.firstOrNull {
        it.parameterCount == 0 && it.name.startsWith(prefix)
    } ?: error("Missing no-arg method starting with '$prefix' on ${javaClass.name}")
    return method.invoke(this)
}

fun box(): String {
    val definitionClass = Class.forName("test.box.EchoService_WasmlineDefinition")
    val definition = definitionClass
        .getField("INSTANCE")
        .get(null)

    val contract = definition.invokeNoArgMethod("getContract")
    if (contract != EchoService::class) {
        return "Fail contract=$contract"
    }

    val serviceId = definition.invokeNoArgMethod("getServiceId")
    val serviceIdValue = when (serviceId) {
        is String -> serviceId
        null -> null
        else -> serviceId.invokeNoArgMethod("getValue")
    }
    if (serviceIdValue != "test.box.EchoService") {
        return "Fail serviceId=$serviceIdValue"
    }

    val linkMethod = definitionClass.methods.singleOrNull { it.name == "link" && it.parameterCount == 1 }
        ?: return "Fail missingLinkMethod"
    if (linkMethod.parameterTypes.single().name != "kotlin.jvm.functions.Function2") {
        return "Fail linkParamType=${linkMethod.parameterTypes.single().name}"
    }

    val bindMethod = definitionClass.methods.singleOrNull { it.name == "bind" && it.parameterCount == 2 }
        ?: return "Fail missingBindMethod"
    if (bindMethod.parameterTypes[0] != EchoService::class.java) {
        return "Fail bindImplType=${bindMethod.parameterTypes[0].name}"
    }
    if (bindMethod.parameterTypes[1].name != "kotlin.jvm.functions.Function2") {
        return "Fail bindRegistrarType=${bindMethod.parameterTypes[1].name}"
    }

    var action: String? = null
    var payload: ByteArray? = null
    val invokeAction = object : Function2<String, ByteArray, ByteArray> {
        override fun invoke(invokedAction: String, invokedPayload: ByteArray): ByteArray {
            action = invokedAction
            payload = invokedPayload.copyOf()
            return "reply:${payload!!.decodeToString()}".encodeToByteArray()
        }
    }

    val proxy = linkMethod.invoke(definition, invokeAction) as EchoService
    if (proxy::class.qualifiedName != "test.box.EchoService_WasmlineProxy") {
        return "Fail proxyType=${proxy::class.qualifiedName}"
    }

    if (proxy !is EchoService) {
        return "Fail proxyContractType=${proxy::class.qualifiedName}"
    }

    val result = proxy.echo("hello".encodeToByteArray()).decodeToString()
    if (result != "reply:hello") {
        return "Fail result=$result"
    }
    if (action != "test.box.EchoService#xjRgE7w2") {
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
    bindMethod.invoke(definition, EchoServiceImpl(), registerAction)

    val boundProxy = linkMethod.invoke(
        definition,
        object : Function2<String, ByteArray, ByteArray> {
            override fun invoke(invokedAction: String, invokedPayload: ByteArray): ByteArray {
                val handler = boundHandlers[invokedAction] ?: error("Missing handler $invokedAction")
                return handler.invoke(invokedPayload)
            }
        },
    ) as EchoService
    val boundResult = boundProxy.echo("bound".encodeToByteArray()).decodeToString()
    if (boundResult != "bound") {
        return "Fail boundResult=$boundResult"
    }

    return "OK"
}

