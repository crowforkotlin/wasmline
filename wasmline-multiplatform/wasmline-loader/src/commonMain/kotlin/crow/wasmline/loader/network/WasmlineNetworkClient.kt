package crow.wasmline.loader.network

/**
 * Minimal asynchronous HTTP transport abstraction for remote package loading.
 *
 * The loader never chooses an HTTP engine. Applications opt into an adapter such
 * as `wasmline-network-ktor` or `wasmline-network-okhttp`, or provide their own.
 */
fun interface WasmlineNetworkClient {
    suspend fun fetch(url: String): WasmlineHttpResponse
}
