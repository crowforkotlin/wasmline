package crow.wasmline.samples.minimal

import crow.wasmline.loader.WasmlineLoader
import kotlinx.coroutines.Dispatchers

fun desktopEnvironment() = PluginEnvironment(Dispatchers.IO) {
    val manifest = requireNotNull(System.getProperty("wasmline.sample.manifest")) {
        "Start this sample with the Gradle run task."
    }
    println("[host] Loading $manifest")
    WasmlineLoader.load(manifest, sampleLoadOptions())
}
