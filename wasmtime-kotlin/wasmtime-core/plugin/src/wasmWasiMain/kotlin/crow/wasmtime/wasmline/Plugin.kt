@file:OptIn(ExperimentalWasmInterop::class)

package crow.wasmtime.wasmline

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

val baseJson = Json {
    prettyPrint = true
    isLenient = true
}

@Serializable
data class User(val id: Int, val name: String)

var data = 1

// 用户只需要在一个地方初始化路由
fun initApp() {
    WasmRouter.register("getUser") { jsonArgs ->
        // 纯粹的业务逻辑
        val start = Clock.System.now().toEpochMilliseconds()
        val user = User(1, "Crow Optimized \t $data")
        data = (0..100000).random()
        val result =  "123123123\t$data"
        val end  = Clock.System.now().toEpochMilliseconds()
        println("SPEND TIME : ${end - start} MS")
        jsonArgs
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