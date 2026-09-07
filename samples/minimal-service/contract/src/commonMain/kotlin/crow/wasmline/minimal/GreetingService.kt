package crow.wasmline.minimal

import crow.wasmline.WasmlineService

interface GreetingService : WasmlineService {
    fun greet(name: String): String
}
