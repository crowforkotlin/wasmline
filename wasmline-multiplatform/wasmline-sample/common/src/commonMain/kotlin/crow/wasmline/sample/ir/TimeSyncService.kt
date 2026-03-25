package crow.wasmline.sample.ir

import crow.wasmline.WasmlineService

interface TimeSyncService : WasmlineService {
    fun timeSync(payload: ByteArray): ByteArray
}

