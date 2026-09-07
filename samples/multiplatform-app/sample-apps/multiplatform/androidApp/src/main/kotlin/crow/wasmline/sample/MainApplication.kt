package crow.wasmline.sample

import android.app.Application
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.WasmlineRuntime

/**
 * Date: 2026-01-02
 * Author: crowforkotlin
 * @formatter:on
 */
class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WasmlineRuntime.warmUp(WasmlineEngineKind.PULLEY)
    }
}
