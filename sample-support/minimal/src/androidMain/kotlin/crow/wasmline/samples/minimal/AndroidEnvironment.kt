package crow.wasmline.samples.minimal

import android.content.Context
import crow.wasmline.loader.WasmlineLoader
import kotlinx.coroutines.Dispatchers
import java.io.File

fun androidEnvironment(context: Context): PluginEnvironment {
    val applicationContext = context.applicationContext
    return PluginEnvironment(Dispatchers.IO) {
        val directory = File(applicationContext.cacheDir, "minimal-plugin")
        copyAssets(applicationContext, "plugin", directory)
        val manifest = File(directory, "manifest.wlm").absolutePath
        println("[host] Loading $manifest")
        WasmlineLoader.load(manifest, sampleLoadOptions())
    }
}

private fun copyAssets(context: Context, source: String, destination: File) {
    val children = context.assets.list(source).orEmpty()
    if (children.isEmpty()) {
        check(destination.parentFile?.isDirectory == true || destination.parentFile?.mkdirs() == true) {
            "Cannot create plugin cache directory."
        }
        context.assets.open(source).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
    } else {
        children.forEach { child -> copyAssets(context, "$source/$child", File(destination, child)) }
    }
}
