package crow.wasmline.network

/**
 * HTTP response returned by [WasmlineNetworkClient.fetch].
 *
 * @property statusCode HTTP status code (e.g. 200, 404).
 * @property bytes Response body as raw bytes.
 */
data class WasmlineHttpResponse(val statusCode: Int, val bytes: ByteArray) {
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
