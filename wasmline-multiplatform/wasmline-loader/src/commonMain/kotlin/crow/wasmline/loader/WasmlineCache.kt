package crow.wasmline.loader

/**
 * Pluggable key-value byte store for caching downloaded manifests and artifacts.
 *
 * Keys are typically prefixed:
 * - `manifest_{sha256(url)}` for manifest envelopes
 * - `artifact_{sha256}` for compiled artifacts (content-addressed)
 *
 * The default file-system implementation is available via [WasmlineFileCache].
 * A no-op implementation [WasmlineNoOpCache] is provided for disabling cache.
 *
 * 2026-06-02
 * @author crowforkotlin
 */
interface WasmlineCache {
    /**
     * Retrieve cached bytes, or `null` if the key is not present.
     */
    fun get(key: String): ByteArray?

    /**
     * Store [bytes] under [key], replacing any existing entry.
     */
    fun put(key: String, bytes: ByteArray)

    /**
     * Check whether [key] exists without reading its content.
     */
    fun exists(key: String): Boolean
}

/**
 * No-op cache that never stores or retrieves data.
 *
 * Use this when caching is not desired (e.g. testing, ephemeral loads).
 */
object WasmlineNoOpCache : WasmlineCache {
    override fun get(key: String): ByteArray? = null
    override fun put(key: String, bytes: ByteArray) {}
    override fun exists(key: String): Boolean = false
}
