package crow.mordecai.wasmline.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import crow.mordecai.wasmline.Wasmline
import crow.mordecai.wasmline.onFailure
import crow.mordecai.wasmline.onSuccess
import crow.mordecai.wasmline.sample.extensions.getPlatformBean
import crow.mordecai.wasmline.sample.extensions.info
import crow.wasmline.sample.bean.PlatformBean
import crow.wasmline.sample.extensions.baseProtobuf
import crow.wasmline.sample.extensions.toJsonString
import crow.wasmline.sample.extensions.toProtoBean
import crow.wasmline.sample.extensions.toProtoBytes
import kotlinx.serialization.decodeFromByteArray
import kotlin.time.measureTime

internal class WasmLoader {

    private var wasmline: Wasmline? by mutableStateOf(null)

    suspend fun loadWasm(wasmAbsPath: String): Wasmline? {
        if (wasmline == null) {
            Wasmline.load(filepath = wasmAbsPath, cacheFilepath = wasmAbsPath, threadSafe = false)
                .onSuccess { this@WasmLoader.wasmline = wasmline; "[WasmLoader] Success : ${this.code}".info() }
                .onFailure { "[WasmLoader] Failure : ${this.cause}".info() }
        }
        return wasmline
    }

    suspend fun timeSync(): PlatformBean? {
        var platformBean: PlatformBean? = null
        if (wasmline != null) {
            val duration = measureTime {
                val platform = getPlatformBean()
                "[WasmLoader] call time sync platform:  $platform".info()
                val bytes = wasmline!!.call(action = "timeSync", inputBytes = toProtoBytes<PlatformBean>(value = platform))

                "[WasmLoader] wasm bytes:  ${bytes.size}".info()
                "[WasmLoader] HEX: ${bytes.toHexString()}".info()
                // 【新增诊断代码】
                if (bytes.size >= 3) {
                    val b0 = bytes[0].toInt() and 0xFF
                    val b1 = bytes[1].toInt() and 0xFF
                    val b2 = bytes[2].toInt() and 0xFF
                    println("[Kotlin Debug] Byte[0]=$b0 (Ex: 10), Byte[1]=$b1 (Ex: 18), Byte[2]=$b2 (Ex: 107)")

                    // 如果这里打印出来不是 10, 18, 107，说明 JNI 到 Java 的数据拷贝错位了
                }

                platformBean = baseProtobuf.decodeFromByteArray(PlatformBean.serializer(),bytes)
            }
            "[WasmLoader] call time sync spend : ${duration.inWholeMilliseconds} ms".info()
        }
        "[WasmLoader] call time sync platform bean is : ${toJsonString(value = platformBean)}".info()
        return platformBean
    }
}