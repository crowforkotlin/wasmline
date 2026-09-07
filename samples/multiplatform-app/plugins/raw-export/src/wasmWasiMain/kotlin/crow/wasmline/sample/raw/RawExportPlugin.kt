@file:OptIn(kotlin.wasm.ExperimentalWasmInterop::class)

package crow.wasmline.sample.raw

import kotlin.wasm.WasmExport

/** Direct Core Wasm export used by the RAW_EXPORT sample. */
@WasmExport("add_i32")
fun addI32(left: Int, right: Int): Int = left + right
