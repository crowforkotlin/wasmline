package crow.wasmline.samples.minimal.library.platform

import crow.wasmline.loader.WasmlineLoader
import crow.wasmline.samples.minimal.library.runtime.PluginEnvironment
import crow.wasmline.samples.minimal.library.security.sampleLoadOptions
import kotlinx.coroutines.Dispatchers

fun desktopEnvironment() = PluginEnvironment(Dispatchers.IO) {
    val manifest = requireNotNull(System.getProperty("wasmline.sample.manifest")) {
        "Start this sample with the Gradle run task."
    }
    println("[host] Loading $manifest")
    WasmlineLoader.load(manifest, sampleLoadOptions())
}
