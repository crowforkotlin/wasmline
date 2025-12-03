@file:OptIn(ExperimentalWasmInterop::class)
@file:Suppress("FunctionName", "unused")

package crow.wasmtime.wasmline

// 用户只需要在一个地方初始化路由
fun initApp() {
    WasmRouter.register("getUser") { jsonArgs -> jsonArgs }
    WasmRouter.register("add") { "{\"result\": 999}" }
}


@WasmExport
fun WasmEntry() { WasmEntryInitialize() }

fun main() { initApp() }