@file:OptIn(ExperimentalWasmInterop::class)

package crow.wasmtime.wasmline

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class User(val id: Int, val name: String)

// 用户只需要在一个地方初始化路由
fun initApp() {
    WasmRouter.register("getUser") { jsonArgs ->
        // 纯粹的业务逻辑
        val user = User(1, "Crow Optimized")
        Json.encodeToString(user)
    }

    WasmRouter.register("add") {
        "{\"result\": 999}"
    }
    WasmRouter.register("A") {
        println("A Running.................")
        var count = 0L
        while (true) {
            count ++
            if (count > Int.MAX_VALUE) {
                count = 0
            }
        }
        "{AAA}"
    }
    WasmRouter.register("B") {
        repeat(10000) {

        }
        "{BBB}"
    }
}

@WasmExport
fun run_entry() { RunWasmEngineEntry() }

fun main() { initApp() }