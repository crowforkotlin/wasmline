// WITH_STDLIB

package test.box

import crow.wasmline.WasmlineBindingScope
import crow.wasmline.WasmlineEndpoint
import crow.wasmline.WasmlineService
import crow.wasmline.WasmlineServiceDefinition

interface EchoService : WasmlineService {
    fun echo(payload: ByteArray): ByteArray
}

private class RecordingEndpoint : WasmlineEndpoint {
    var action: String? = null
    var payload: ByteArray? = null

    override fun invoke(action: String, payload: ByteArray): ByteArray {
        this.action = action
        this.payload = payload.copyOf()
        return "reply:${payload.decodeToString()}".encodeToByteArray()
    }
}

private class EchoServiceImpl : EchoService {
    override fun echo(payload: ByteArray): ByteArray = payload
}

fun box(): String {

    @Suppress("UNCHECKED_CAST")
    val definition = Class.forName("test.box.EchoService_WasmlineDefinition")
        .getField("INSTANCE")
        .get(null) as WasmlineServiceDefinition<EchoService>

    if (definition.contract != EchoService::class) {
        return "Fail contract=${definition.contract}"
    }
    if (definition.serviceId.value != "test.box.EchoService") {
        return "Fail serviceId=${definition.serviceId.value}"
    }

    val endpoint = RecordingEndpoint()
    val proxy = definition.link(endpoint)
    if (proxy !is EchoService) {
        return "Fail proxyContractType=${proxy::class.qualifiedName}"
    }
    if (proxy::class.qualifiedName != "test.box.EchoService_WasmlineProxy") {
        return "Fail proxyType=${proxy::class.qualifiedName}"
    }

    val result = proxy.echo("hello".encodeToByteArray()).decodeToString()
    if (result != "reply:hello") {
        return "Fail result=$result"
    }
    if (endpoint.action != "test.box.EchoService#xjRgE7w2") {
        return "Fail action=${endpoint.action}"
    }
    if (endpoint.payload?.decodeToString() != "hello") {
        return "Fail payload=${endpoint.payload?.decodeToString()}"
    }

    val bindError = try {
        definition.bind(EchoServiceImpl(), WasmlineBindingScope())
        "NO_ERROR"
    } catch (e: IllegalStateException) {
        e.message.orEmpty()
    }
    if (bindError != "Wasmline adapter method generation is not implemented yet for test.box.EchoService.") {
        return "Fail bindError=$bindError"
    }

    return "OK"
}

