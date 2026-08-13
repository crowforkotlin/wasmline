package crow.wasmline.sample

import android.app.Application
import crow.wasmline.Wasmline
import crow.wasmline.WasmlineWarmupMode
import crow.wasmline.loader.WasmlineLoader

/**
 * Date: 2026-01-02
 * Author: crowforkotlin
 * @formatter:on
 */
class MainApplication: Application() {
    override fun onCreate() {
        super.onCreate()
        WasmlineLoader.bootstrap()
        WasmlineLoader.warmup(WasmlineWarmupMode.PULLEY)
    }
}