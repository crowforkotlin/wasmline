package crow.wasmline.loader.network

/**
 * Minimal asynchronous HTTP transport abstraction for remote package loading.
 *
 * The loader never chooses an HTTP engine. Applications opt into an adapter such
 * as `wasmline-network-ktor` or `wasmline-network-okhttp`, or provide their own.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
interface WasmlineNetworkClient {
    /** Fetches a bounded manifest or other small response as bytes. */
    suspend fun fetch(url: String): WasmlineHttpResponse

    /** Streams a response body into [sink] without materializing the complete body. */
    suspend fun fetchTo(url: String, sink: WasmlineNetworkSink): WasmlineHttpStatus
}

/**
 * Consumes synchronous byte ranges supplied by a streaming network response.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
fun interface WasmlineNetworkSink {
    /** Consumes one response-body range before the backing array may be reused. */
    fun write(bytes: ByteArray, offset: Int, byteCount: Int)
}
