package crow.wasmtime.wasmline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.tan
import kotlin.random.Random


@WasmExport
fun add(a: Int, b: Int): Int {
    println("--- Wasm Module: 'add' Starting ---")
    
    val initialTanValue = tan(0.5)
    println("Initial Tan Value: $initialTanValue")

    CoroutineScope(Dispatchers.Default).launch {
        println("*********************** [Coroutine Started] ***********************")
        repeat(3) {
            delay(1000)
            println("repeat count : $it")
        }
    }

    val rangeSize = 5
    println("Starting loop from 0 to ${rangeSize - 1}...")


    println("\nLoop finished.")
    
    println("--- Wasm Module: 'add' Finished. Returning sum. ---")
    return a + b
}