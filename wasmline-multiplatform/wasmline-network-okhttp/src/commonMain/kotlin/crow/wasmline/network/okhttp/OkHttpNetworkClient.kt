package crow.wasmline.network.okhttp

import crow.wasmline.network.WasmlineHttpResponse
import crow.wasmline.network.WasmlineNetworkClient
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * [WasmlineNetworkClient] implementation backed by OkHttp 5.x.
 *
 * Uses OkHttp's blocking [okhttp3.Call.execute] API, which is safe to call
 * from any thread (including Android background threads and JVM worker threads).
 *
 * Example:
 * ```kotlin
 * val result = loadWasmline(
 *     WasmlineLoadRequest(
 *         source = WasmlineSource.RemoteManifestUrl("https://example.com/plugin"),
 *         networkClient = OkHttpNetworkClient(),
 *     ),
 * )
 * ```
 *
 * @param client Optional pre-configured [OkHttpClient] for connection pooling,
 *               custom timeouts, interceptors, etc.
 */
class OkHttpNetworkClient(private val client: OkHttpClient = OkHttpClient()) : WasmlineNetworkClient {

    override fun fetch(url: String): WasmlineHttpResponse {
        val request = Request.Builder()
            .url(url)
            .build()
        val response = client.newCall(request).execute()
        return response.use { resp ->
            WasmlineHttpResponse(
                statusCode = resp.code,
                bytes = resp.body.bytes(),
            )
        }
    }
}

/**
 * Factory function for creating an [OkHttpNetworkClient].
 */
fun okHttpNetworkClient(client: OkHttpClient = OkHttpClient()): WasmlineNetworkClient = OkHttpNetworkClient(client)
