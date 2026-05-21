@file:OptIn(ExperimentalWasmInterop::class, ExperimentalSerializationApi::class)
@file:Suppress("FunctionName", "unused", "SpellCheckingInspection")

package crow.wasmline.sample

import crow.wasmline.Wasmline
import crow.wasmline.bind
import crow.wasmline.link
import crow.wasmline.sample.bean.PlatformBean
import crow.wasmline.sample.ir.EchoService
import crow.wasmline.sample.ir.TimeSyncService
import crow.wasmline.serialization.WasmlineProtobufSerializationFactory
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.time.Clock

val wasmline = Wasmline.current.configure { serialization(WasmlineProtobufSerializationFactory) }

fun main() {

    println("[Kotlin Wasi] Plugin main executed")
    wasmline.bind(object : TimeSyncService {
        override fun timeSync(platform: PlatformBean): PlatformBean {
            wasmline.link<EchoService>().echo()
            println("[Kotlin Wasi] Plugin \"timeSync\" receive bean : ${platform}")
            return PlatformBean(
                platform = "kotlin wasi plugin, host parms : $platform",
                content = "hello from kotlin wasm.",
                timeStr = "${Clock.System.now()}",
                timeMs = Clock.System.now().toEpochMilliseconds()
            ).also {
                println("wasi wasm plugin return bean is : $it")
            }
        }
    })
}
