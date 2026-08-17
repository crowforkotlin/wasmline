package crow.wasmline.loader

/**
 * Pluggable key-value byte store for cached manifests and artifacts.
 *
 * The loader owns the cache policy and key format. Implementations only need to
 * provide byte storage and may back it with memory, files, a database, or a
 * platform-specific store.
 */
interface WasmlineCache {
    fun get(key: String): ByteArray?
    fun put(key: String, bytes: ByteArray)
    fun exists(key: String): Boolean
}

/** Cache implementation that never stores or retrieves data. */
object WasmlineNoOpCache : WasmlineCache {
    override fun get(key: String): ByteArray? = null

    override fun put(key: String, bytes: ByteArray) {}

    override fun exists(key: String): Boolean = false
}
