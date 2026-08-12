package crow.wasmline.sample.ir

import crow.wasmline.WasmlineService

/*
 * Copy-ready Wasmline IR fixture.
 *
 * Uncomment and adapt locally when you want to smoke-test the compiler plugin:
 *
 * import crow.wasmline.WasmlineService
 *

 */

interface EchoService : WasmlineService {
    fun echo()
}

class EchoServiceImpl : EchoService {
    override fun echo() {}
}
