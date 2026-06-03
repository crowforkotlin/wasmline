package crow.wasmline

/**
 * Pluggable key-value byte store for caching downloaded manifests and artifacts.
 *
 * Keys are typically prefixed:
 * - `manifest_{sha256(url)}` for manifest envelopes
 * - `artifact_{sha256}` for compiled artifacts (content-addressed)
 */
interface WasmlineCache {
    fun get(key: String): ByteArray?
    fun put(key: String, bytes: ByteArray)
    fun exists(key: String): Boolean
}

/**
 * No-op cache that never stores or retrieves data.
 */
object WasmlineNoOpCache : WasmlineCache {
    override fun get(key: String): ByteArray? = null
    override fun put(key: String, bytes: ByteArray) {}
    override fun exists(key: String): Boolean = false
}
