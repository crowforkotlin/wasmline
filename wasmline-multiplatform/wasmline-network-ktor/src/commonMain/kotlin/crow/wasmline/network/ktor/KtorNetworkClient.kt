package crow.wasmline.network.ktor

import crow.wasmline.loader.network.WasmlineHttpResponse
import crow.wasmline.loader.network.WasmlineNetworkClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes

/**
 * [WasmlineNetworkClient] implementation backed by Ktor HttpClient.
 *
 * Ktor selects the appropriate engine per platform:
 * - JVM: CIO engine
 * - Android: OkHttp engine
 * - iOS: Darwin engine
 * Browser targets use the raw `.wasm` prefetch flow and are intentionally not
 * published by this adapter.
 *
 * @param client Optional pre-configured [HttpClient] for custom configuration.
 */
class KtorNetworkClient(private val client: HttpClient = HttpClient()) : WasmlineNetworkClient {

    override suspend fun fetch(url: String): WasmlineHttpResponse {
        val response = client.get(url)
        return WasmlineHttpResponse(
            statusCode = response.status.value,
            bytes = response.bodyAsBytes(),
        )
    }
}

/**
 * Factory function for creating a [KtorNetworkClient].
 */
fun ktorNetworkClient(client: HttpClient = HttpClient()): WasmlineNetworkClient = KtorNetworkClient(client)
