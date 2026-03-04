@file:OptIn(ExperimentalForeignApi::class)

package crow.wasmline.native

import crow.wasmline.native.c.*
import kotlinx.cinterop.*

object WasmlineBridge {
    fun init() {
       wasmline_init_engine()
    }
}