// WITH_STDLIB

@file:JvmName("LinkSelectsGeneratedBridgeBox")

package test.box

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineService
import crow.wasmline.link
import java.io.DataInputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

interface LinkedEchoService : WasmlineService {
    fun echo(payload: ByteArray): ByteArray
}

private object LinkBinding {
    @Suppress("unused")
    fun obtain(wasmline: Wasmline): LinkedEchoService {
        return wasmline.link<LinkedEchoService>()
    }
}

fun box(): String {
    if (runCatching { Class.forName("test.box.LinkedEchoService_WasmlineDefinition") }.isSuccess) {
        return "Fail legacyDefinitionStillExists"
    }

    val bridgeClass = Class.forName("test.box.LinkedEchoService_WasmlineBridge")
    if (!LinkedEchoService::class.java.isAssignableFrom(bridgeClass)) {
        return "Fail bridgeContractType=${bridgeClass.name}"
    }

    val endpointCtor = bridgeClass.declaredConstructors.singleOrNull { ctor ->
        ctor.parameterTypes.singleOrNull()?.name == "crow.wasmline.internal.bridge.WasmlineEndpoint"
    } ?: return "Fail missingEndpointCtor"

    val constants = classUtf8Constants(LinkBinding::class.java)
    if (constants.any { it.contains("WasmlineServices_hostKt") }) {
        return "Fail stillReferencesHostPlaceholder"
    }
    if (constants.none { it.contains("LinkedEchoService_WasmlineBridge") }) {
        return "Fail missingBridgeReference"
    }
    if (constants.none { it.contains("GeneratedWasmlineHostEndpoint") }) {
        return "Fail missingGeneratedEndpointReference"
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

    val proxy = endpointCtor.newInstance(linkedEndpoint) as LinkedEchoService
    val result = proxy.echo("hello".encodeToByteArray()).decodeToString()
    if (result != "reply:hello") {
        return "Fail result=$result"
    }
    if (action != "test.box.LinkedEchoService#echo") {
        return "Fail action=$action"
    }
    if (payload?.decodeToString() != "hello") {
        return "Fail payload=${payload?.decodeToString()}"
    }

    return "OK"
}

private fun classUtf8Constants(type: Class<*>): Set<String> {
    val resourcePath = type.name.replace('.', '/') + ".class"
    val stream = type.classLoader.getResourceAsStream(resourcePath)
        ?: error("Unable to read class bytes for ${type.name}")
    DataInputStream(stream.buffered()).use { input ->
        val magic = input.readInt()
        check(magic == 0xCAFEBABE.toInt()) { "Unexpected class file magic for ${type.name}: $magic" }
        input.readUnsignedShort()
        input.readUnsignedShort()
        val constantPoolCount = input.readUnsignedShort()
        val utf8Entries = linkedSetOf<String>()
        var index = 1
        while (index < constantPoolCount) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8Entries += input.readUTF()
                3, 4 -> input.skipBytes(4)
                5, 6 -> {
                    input.skipBytes(8)
                    index += 1
                }
                7, 8, 16, 19, 20 -> input.skipBytes(2)
                9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
                15 -> input.skipBytes(3)
                else -> error("Unsupported constant-pool tag $tag for ${type.name}")
            }
            index += 1
        }
        return utf8Entries
    }
}

