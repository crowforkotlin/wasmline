@file:OptIn(ExperimentalWasmInterop::class, ExperimentalSerializationApi::class)
@file:Suppress("FunctionName")

import crow.wasmtime.wasmline.WasmRouter
import crow.wasmtime.wasmline.WasmlineInitialize
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.time.Clock


@WasmExport("InitWasmline")
fun InitWasmline(actionLen: Int, inputLen: Int) { WasmlineInitialize(actionLen, inputLen) }

@Serializable
data class Data(
    val id: Long,
    val name: String,
    val key: String
)

fun main() {
    WasmRouter.register("add") { value ->
        if (value != null) {
            println("value is ---------> value ${ProtoBuf.decodeFromByteArray<Data>(value)}")
        }
        ProtoBuf.encodeToByteArray(Data(id = 1024, name = "Hello from wasm : ${Clock.System.now()}", key = "kotlin/wasi"))
    }
}