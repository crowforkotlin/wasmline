package crow.wasmline.loader.network

/**
 * Minimal asynchronous HTTP transport abstraction for remote package loading.
 *
 * The loader never chooses an HTTP engine. Applications opt into an adapter such
 * as `wasmline-network-ktor` or `wasmline-network-okhttp`, or provide their own.
 */
fun interface WasmlineNetworkClient {
    suspend fun fetch(url: String): WasmlineHttpResponse

    /**
     * Streams a response body into [sink]. The sink consumes each byte range
     * synchronously; implementations may reuse the supplied array afterward.
     *
     * The default implementation preserves compatibility with simple clients
     * by delegating to [fetch]. Network adapters should override this method to
     * avoid materializing large artifact responses in memory.
     */
    suspend fun fetchTo(url: String, sink: WasmlineNetworkSink): WasmlineHttpStatus {
        val response = fetch(url)
        if (response.isSuccess) {
            sink.write(response.bytes, offset = 0, byteCount = response.bytes.size)
        }
        return WasmlineHttpStatus(response.statusCode)
    }
}

/** Synchronous byte consumer used by streaming network responses. */
fun interface WasmlineNetworkSink {
    fun write(bytes: ByteArray, offset: Int, byteCount: Int)
}
