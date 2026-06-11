package crow.wasmline.network

/**
 * Minimal HTTP transport abstraction for remote package loading.
 *
 * Implementations should perform a blocking HTTP GET and return the response.
 * Async HTTP engines (OkHttp enqueue, Ktor coroutines) must be bridged to
 * blocking internally — the loader resolver chain is synchronous.
 *
 * Official adapters:
 * - `wasmline-network-okhttp` (JVM/Android, OkHttp 5.x)
 * - `wasmline-network-ktor` (Multiplatform, Ktor 3.x)
 */
fun interface WasmlineNetworkClient {
    fun fetch(url: String): WasmlineHttpResponse
}
