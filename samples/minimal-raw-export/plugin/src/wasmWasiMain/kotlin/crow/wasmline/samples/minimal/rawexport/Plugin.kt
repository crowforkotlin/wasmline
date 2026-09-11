@file:OptIn(kotlin.wasm.ExperimentalWasmInterop::class)

package crow.wasmline.samples.minimal.rawexport

import kotlin.wasm.WasmExport

@WasmExport("add_i32")
fun addI32(left: Int, right: Int): Int = left + right
