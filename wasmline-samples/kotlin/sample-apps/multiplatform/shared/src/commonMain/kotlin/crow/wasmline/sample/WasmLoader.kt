@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package crow.wasmline.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import crow.wasmline.Wasmline
import crow.wasmline.bind
import crow.wasmline.link
import crow.wasmline.onFailure
import crow.wasmline.onSuccess
import crow.wasmline.loader.loadWasmline
import crow.wasmline.sample.extensions.getPlatformBean
import crow.wasmline.sample.extensions.info
import crow.wasmline.sample.bean.PlatformBean
import crow.wasmline.sample.extensions.baseProtobuf
import crow.wasmline.sample.extensions.toJsonString
import crow.wasmline.sample.extensions.toProtoBytes
import crow.wasmline.sample.ir.EchoService
import crow.wasmline.sample.ir.TimeSyncService
import kotlin.time.measureTime

internal class WasmLoader {

    private var wasmline: Wasmline? by mutableStateOf(null)

    fun loadWasm(artifactAbsPath: String): Wasmline? {
        if (wasmline == null) {
            loadWasmline(artifactPath = artifactAbsPath, threadSafe = false)
                .onSuccess {
                    wasmline.bind(object : EchoService {
                        override fun echo() {
                            "[WasmLoader] plugin invoked host echo()".info()
                        }
                    })
                    this@WasmLoader.wasmline = wasmline
                    "[WasmLoader] Success : ${this.code}".info()
                }
                .onFailure { "[WasmLoader] Failure : ${this.cause}".info() }
        }
        return wasmline
    }

    fun timeSync(): PlatformBean? {
        var platformBean: PlatformBean? = null
        if (wasmline != null) {
            val duration = measureTime {
                val platform = getPlatformBean()
                "[WasmLoader] call time sync platform:  $platform".info()
                val bytes = wasmline!!.link<TimeSyncService>()
                    .timeSync(toProtoBytes<PlatformBean>(value = platform))
                platformBean = baseProtobuf.decodeFromByteArray(PlatformBean.serializer(),bytes)
            }
            "[WasmLoader] call time sync spend : ${duration.inWholeMilliseconds} ms".info()
        }
        "[WasmLoader] call time sync platform bean is : ${toJsonString(value = platformBean)}".info()
        return platformBean
    }
}