package crow.wasmline.samples.minimal.service.common

import crow.wasmline.WasmlineService

interface GreetingService : WasmlineService {
    fun greet(name: String): String
}
