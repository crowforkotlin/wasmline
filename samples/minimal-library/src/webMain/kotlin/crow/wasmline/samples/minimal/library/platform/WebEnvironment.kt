package crow.wasmline.samples.minimal.library.platform

import crow.wasmline.loader.WasmlineLoader
import crow.wasmline.network.ktor.KtorNetworkClient
import crow.wasmline.samples.minimal.library.runtime.PluginEnvironment
import crow.wasmline.samples.minimal.library.security.sampleLoadOptions
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers

fun webEnvironment(manifestUrl: String) = PluginEnvironment(Dispatchers.Default) {
    val client = HttpClient()
    try {
        println("[host] Loading $manifestUrl")
        WasmlineLoader.load(manifestUrl, sampleLoadOptions(KtorNetworkClient(client)))
    } finally {
        client.close()
    }
}
