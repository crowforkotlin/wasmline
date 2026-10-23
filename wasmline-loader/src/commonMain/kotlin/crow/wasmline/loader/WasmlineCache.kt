package crow.wasmline.loader

/**
 * Pluggable key-value byte store for cached manifests and artifacts.
 *
 * The loader owns the cache policy and key format. Implementations only need to
 * provide byte storage and may back it with memory, files, a database, or a
 * platform-specific store. Persistent implementations must publish each [put]
 * atomically so concurrent readers never observe a partial value.
 *
 * Keys are opaque loader-generated strings. Implementations must store and
 * return every key verbatim, including the `.timestamp` companion entries used
 * to enforce [WasmlineLoadOptions.manifestTtlMs]; rewriting, case folding, or
 * truncating keys breaks manifest cache reuse. Entries are independent, so
 * entries may be written and evicted in any order.
 */
interface WasmlineCache {
    fun get(key: String): ByteArray?

    fun put(key: String, bytes: ByteArray)
}

/** Cache implementation that never stores or retrieves data. */
object WasmlineNoOpCache : WasmlineCache {
    override fun get(key: String): ByteArray? = null

    override fun put(key: String, bytes: ByteArray) {}
}
