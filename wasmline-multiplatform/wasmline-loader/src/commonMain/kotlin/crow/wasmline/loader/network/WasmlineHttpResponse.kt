package crow.wasmline.loader.network

/**
 * Contains a bounded in-memory HTTP response returned by [WasmlineNetworkClient.fetch].
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
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

/**
 * Contains HTTP status metadata for a body streamed directly to a sink.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
data class WasmlineHttpStatus(val statusCode: Int) {
    val isSuccess: Boolean get() = statusCode in 200..299
}
