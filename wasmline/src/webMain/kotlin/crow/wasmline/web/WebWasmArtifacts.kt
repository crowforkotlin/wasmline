package crow.wasmline.web

/**
 * Byte cache bridging Fetch downloads and the Wasmline loader.
 *
 * Web callers prefetch each raw artifact first; the loader then resolves the
 * cached bytes and instantiates the module without routing raw `.wasm` through
 * the signed remote-package pipeline.
 *
 * Date: 2026-07-29
 * Author: crowforkotlin
 */
internal object WebWasmArtifacts {

    private class Entry(val bytes: ByteArray, val identity: Any = Any())

    private val cache = mutableMapOf<String, Entry>()

    /** Registers a defensive copy of caller-trusted raw Wasm bytes. */
    fun register(key: String, bytes: ByteArray) {
        require(key.isNotBlank()) { "Web Wasm artifact key must not be blank." }
        require(bytes.isNotEmpty()) { "Web Wasm artifact bytes must not be empty." }
        cache[key] = Entry(bytes.copyOf())
    }

    /** Downloads and caches the artifact, reporting completion via callbacks. */
    fun prefetch(url: String, onReady: () -> Unit, onFailure: (String) -> Unit) {
        if (cache.containsKey(url)) {
            onReady()
            return
        }
        WebArtifactFetcher.fetch(
            url = url,
            onSuccess = { bytes ->
                cache[url] = Entry(bytes.copyOf())
                onReady()
            },
            onFailure = onFailure,
        )
    }

    /** Suspending variant; throws [WebWasmException] on any failure. */
    suspend fun prefetch(url: String) {
        if (cache.containsKey(url)) return
        cache[url] = Entry(WebArtifactFetcher.fetch(url).copyOf())
    }

    /** Returns cached bytes, or null when the artifact was never prefetched. */
    fun bytesOrNull(url: String): ByteArray? = cache[url]?.bytes?.copyOf()

    /** Opaque identity changes on every byte replacement, including identical bytes. */
    fun identityOrNull(key: String): Any? = cache[key]?.identity

    /** Returns whether bytes are cached under [url] without copying them. */
    fun contains(url: String): Boolean = cache.containsKey(url)

    fun invalidate(url: String) {
        cache.remove(url)
    }

    fun clear() {
        cache.clear()
    }
}
