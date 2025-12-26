@file:Suppress("FunctionName", "unused")

package crow.wasmtime.wasmline

fun WasmEntryInitialize() {
    // 1. 自动拉取参数
    val action = WasmBridge.getAction()
    val args = WasmBridge.getJson()
    println("[WasmKotlin] Core --> Receive action is : $action \t arg is : ${if (args.length > 32) "${args.take(n = 32)}......" else args}")

    // 2. 自动捕获异常并分发
    val result = try {
        WasmRouter.dispatch(action, args)
    } catch (exception: Exception) {
        println("[WasmKotlin] Core --> Exception message : ${exception.message}")
        """{"error": "Wasm Panic: ${exception.message}"}"""
    }

    val logMsg = "Wasm received: $args"
    WasmBridge.callHost("host_log", logMsg.encodeToByteArray())

    // 3. 自动回传
    WasmBridge.sendResult(result)
}

fun main() { println("[Wasm SDK] Initialized.") }