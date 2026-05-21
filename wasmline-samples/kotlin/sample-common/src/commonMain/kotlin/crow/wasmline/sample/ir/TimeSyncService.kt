package crow.wasmline.sample.ir

import crow.wasmline.WasmlineService
import crow.wasmline.sample.bean.PlatformBean

interface TimeSyncService : WasmlineService {
    fun timeSync(platform: PlatformBean): PlatformBean
}

