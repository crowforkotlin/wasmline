package crow.wasmline.network.ktor

import crow.wasmline.loader.network.WasmlineHttpResponse
import crow.wasmline.loader.network.WasmlineHttpStatus
import crow.wasmline.loader.network.WasmlineNetworkClient
import crow.wasmline.loader.network.WasmlineNetworkSink
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable

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

    override suspend fun fetchTo(url: String, sink: WasmlineNetworkSink): WasmlineHttpStatus {
        val response = client.get(url)
        val status = WasmlineHttpStatus(response.status.value)
        val channel = response.bodyAsChannel()
        if (!status.isSuccess) {
            channel.cancel()
            return status
        }

        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        while (true) {
            val byteCount = channel.readAvailable(buffer)
            if (byteCount < 0) break
            if (byteCount > 0) sink.write(buffer, offset = 0, byteCount = byteCount)
        }
        return status
    }

    private companion object {
        const val STREAM_BUFFER_SIZE: Int = 64 * 1024
    }
}

/**
 * Factory function for creating a [KtorNetworkClient].
 */
fun ktorNetworkClient(client: HttpClient = HttpClient()): WasmlineNetworkClient = KtorNetworkClient(client)
