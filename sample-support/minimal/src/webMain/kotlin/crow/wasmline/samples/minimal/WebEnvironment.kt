package crow.wasmline.samples.minimal

import crow.wasmline.loader.WasmlineLoader
import crow.wasmline.network.ktor.KtorNetworkClient
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
