package crow.mordecai.wasmline

import android.app.Application

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
        Wasmline.init()
    }
}