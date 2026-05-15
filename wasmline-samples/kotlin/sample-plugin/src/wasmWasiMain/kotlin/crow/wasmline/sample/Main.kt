@file:OptIn(ExperimentalWasmInterop::class, ExperimentalSerializationApi::class)
@file:Suppress("FunctionName", "unused")

package crow.wasmline.sample

import crow.wasmline.Wasmline
import crow.wasmline.bind
import crow.wasmline.link
import crow.wasmline.sample.bean.PlatformBean
import crow.wasmline.sample.extensions.toProtoBean
import crow.wasmline.sample.extensions.toProtoBytes
import crow.wasmline.sample.ir.EchoService
import crow.wasmline.sample.ir.TimeSyncService
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.time.Clock

fun main() {
    println("[Kotlin Wasi] Plugin main executed")
    Wasmline.current.bind(object : TimeSyncService {
        override fun timeSync(payload: ByteArray): ByteArray {
            Wasmline.current.link<EchoService>().echo()
            println("[Kotlin Wasi] Plugin \"timeSync\" receive bean : ${toProtoBean<PlatformBean>(payload)}")
            return toProtoBytes(value = PlatformBean(
                platform = "kotlin wasi plugin",
                content = "hello from kotlin wasm.",
                timeStr = "${Clock.System.now()}",
                timeMs = Clock.System.now().toEpochMilliseconds()
            ).also {
                println("wasi wasm plugin return bean is : $it")
            }).also {
                println("wasi wasm plugin return bean to byte size is : ${it.size}")
                println("wasi wasm plugin return bean hex string is : ${it.toHexString()}")
            }
        }
    })
}

