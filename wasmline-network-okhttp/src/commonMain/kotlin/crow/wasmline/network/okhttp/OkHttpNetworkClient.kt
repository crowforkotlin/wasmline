package crow.wasmline.network.okhttp

import crow.wasmline.loader.network.WasmlineHttpResponse
import crow.wasmline.loader.network.WasmlineHttpStatus
import crow.wasmline.loader.network.WasmlineNetworkClient
import crow.wasmline.loader.network.WasmlineNetworkSink
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [WasmlineNetworkClient] implementation backed by OkHttp 5.x.
 *
 * Uses OkHttp's asynchronous callback API and cancels the HTTP call when the
 * caller's coroutine is cancelled.
 *
 * Example:
 * ```kotlin
 * val result = WasmlineLoader.load(
 *     WasmlineLoadRequest(
 *         source = WasmlineSource.RemoteManifestUrl("https://example.com/plugin"),
 *         options = WasmlineLoadOptions(networkClient = OkHttpNetworkClient()),
 *     ),
 * )
 * ```
 *
 * @param client Optional pre-configured [OkHttpClient] for connection pooling,
 *               custom timeouts, interceptors, etc.
 */
class OkHttpNetworkClient(private val client: OkHttpClient = OkHttpClient()) : WasmlineNetworkClient {

    override suspend fun fetch(url: String): WasmlineHttpResponse = execute(url) { response ->
        WasmlineHttpResponse(
            statusCode = response.code,
            bytes = response.body.bytes(),
        )
    }

    override suspend fun fetchTo(url: String, sink: WasmlineNetworkSink): WasmlineHttpStatus = execute(url) { response ->
        val status = WasmlineHttpStatus(response.code)
        if (status.isSuccess) {
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            val source = response.body.source()
            while (true) {
                val byteCount = source.read(buffer, 0, buffer.size)
                if (byteCount < 0) break
                if (byteCount > 0) sink.write(buffer, offset = 0, byteCount = byteCount)
            }
        }
        status
    }

    private suspend fun <T> execute(url: String, transform: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(url)
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use(transform)
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            },
        )
    }

    private companion object {
        const val STREAM_BUFFER_SIZE: Int = 64 * 1024
    }
}

/**
 * Factory function for creating an [OkHttpNetworkClient].
 */
fun okHttpNetworkClient(client: OkHttpClient = OkHttpClient()): WasmlineNetworkClient = OkHttpNetworkClient(client)
