@file:OptIn(ExperimentalForeignApi::class)

package crow.mordecai.wasmline.native

import crow.mordecai.wasmline.native.c.*
import kotlinx.cinterop.*

object WasmlineBridge {
    fun init() {
       wasmline_init_engine()
    }
}