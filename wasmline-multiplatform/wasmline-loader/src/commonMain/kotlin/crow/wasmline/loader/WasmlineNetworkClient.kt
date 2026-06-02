package crow.wasmline.loader

/**
 * Minimal HTTP transport abstraction for remote package loading.
 *
 * Implementations should perform a blocking HTTP GET and return the response.
 * Async HTTP engines (OkHttp enqueue, Ktor coroutines) must be bridged to
 * blocking internally -- the loader resolver chain is synchronous.
 *
 * Official adapters:
 * - `wasmline-network-okhttp` (JVM/Android, OkHttp 5.x)
 * - `wasmline-network-ktor` (Multiplatform, Ktor 3.x)
 *
 * 2026-06-02
 * @author crowforkotlin
 */
fun interface WasmlineNetworkClient {
    fun fetch(url: String): WasmlineHttpResponse
}

/**
 * HTTP response returned by [WasmlineNetworkClient.fetch].
 *
 * @property statusCode HTTP status code (e.g. 200, 404).
 * @property bytes Response body as raw bytes.
 */
data class WasmlineHttpResponse(
    val statusCode: Int,
    val bytes: ByteArray,
) {
    val isSuccess: Boolean get() = statusCode in 200..299

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WasmlineHttpResponse) return false
        return statusCode == other.statusCode && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
