package crow.wasmline.sample.component

import crow.wasmline.WasmlineService
import kotlinx.serialization.Serializable

interface ComponentPluginService : WasmlineService {
    fun echo(request: ComponentEchoRequest): ComponentEchoResponse

    fun callback(payload: ByteArray): ByteArray

    fun empty(): ByteArray

    fun trap(): Unit

    fun initializationCount(): Int
}

interface ComponentHostService : WasmlineService {
    fun callback(payload: ByteArray): ByteArray
}

@Serializable
data class ComponentEchoRequest(val value: String)

@Serializable
data class ComponentEchoResponse(val value: String)
