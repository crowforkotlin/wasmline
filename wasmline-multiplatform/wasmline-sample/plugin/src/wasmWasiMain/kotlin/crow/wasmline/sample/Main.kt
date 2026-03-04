@file:OptIn(ExperimentalWasmInterop::class, ExperimentalSerializationApi::class)
@file:Suppress("FunctionName")

package crow.wasmline.sample

import crow.wasmline.sample.bean.PlatformBean
import crow.wasmline.sample.extensions.toProtoBean
import crow.wasmline.sample.extensions.toProtoBytes
import crow.wasmline.WasmRouter
import crow.wasmline.WasmlineInitialize
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.time.Clock


@WasmExport("InitWasmline")
fun InitWasmline(actionLen: Int, inputLen: Int) { WasmlineInitialize(actionLen, inputLen) }

fun main() {
    WasmRouter.register("timeSync") { bytes ->
        if (bytes != null) {
            println("[Kotlin Wasi] Plugin \"timeSync\" receive bean : ${toProtoBean<PlatformBean>(bytes)}")
        }
        toProtoBytes(value = PlatformBean(
            platform = "kotlin wasi plugin",
            content = "hello from kotlin wasm.",
            timeStr = "${Clock.System.now()}",
            timeMs = Clock.System.now().toEpochMilliseconds()
        ).also {

            println("wasi wasm plugin return bean is : $it")
        }).also {   56
            println("wasi wasm plugin return bean to byte size is : ${it.size}")
            println("wasi wasm plugin return bean hex string is : ${it.toHexString()}")
        }
    }
}