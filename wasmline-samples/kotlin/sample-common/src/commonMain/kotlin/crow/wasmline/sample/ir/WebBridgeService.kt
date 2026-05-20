package crow.wasmline.sample.ir

import crow.wasmline.WasmlineService

interface WebHostService : WasmlineService {
    fun onPluginMessage(payload: ByteArray): ByteArray
}

interface WebPluginService : WasmlineService {
    fun ping(payload: ByteArray): ByteArray
}
