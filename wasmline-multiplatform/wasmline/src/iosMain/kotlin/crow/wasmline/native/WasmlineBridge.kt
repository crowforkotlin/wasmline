@file:OptIn(ExperimentalForeignApi::class)

package crow.wasmline.native

import crow.wasmline.WasmlineWarmupMode
import crow.wasmline.native.c.*
import kotlinx.cinterop.*

object WasmlineBridge {
    fun bootstrap() = Unit

    fun warmup(mode: WasmlineWarmupMode) {
       wasmline_warmup_engine(mode == WasmlineWarmupMode.PULLEY)
    }
}