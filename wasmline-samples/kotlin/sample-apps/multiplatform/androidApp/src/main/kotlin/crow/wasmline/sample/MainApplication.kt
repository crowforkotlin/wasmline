package crow.wasmline.sample

import android.app.Application
import crow.wasmline.Wasmline
import crow.wasmline.WasmlineWarmupMode
import crow.wasmline.loader.WasmlineLoader

/**
 * ● 
 * 
 * ● 2026/1/2 20:54
 * @author crowforkotlin
 * @formatter:on
 */
class MainApplication: Application() {
    override fun onCreate() {
        super.onCreate()
        WasmlineLoader.bootstrap()
        WasmlineLoader.warmup(WasmlineWarmupMode.PULLEY)
    }
}