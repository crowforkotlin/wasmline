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

    private val cache = mutableMapOf<String, ByteArray>()

    /** Registers a defensive copy of caller-trusted raw Wasm bytes. */
    fun register(key: String, bytes: ByteArray) {
        require(key.isNotBlank()) { "Web Wasm artifact key must not be blank." }
        require(bytes.isNotEmpty()) { "Web Wasm artifact bytes must not be empty." }
        cache[key] = bytes.copyOf()
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
                cache[url] = bytes
                onReady()
            },
            onFailure = onFailure,
        )
    }

    /** Suspending variant; throws [WebWasmException] on any failure. */
    suspend fun prefetch(url: String) {
        if (cache.containsKey(url)) return
        cache[url] = WebArtifactFetcher.fetch(url)
    }

    /** Returns cached bytes, or null when the artifact was never prefetched. */
    fun bytesOrNull(url: String): ByteArray? = cache[url]?.copyOf()

    /** Returns whether bytes are cached under [url] without copying them. */
    fun contains(url: String): Boolean = cache.containsKey(url)

    fun invalidate(url: String) {
        cache.remove(url)
    }

    fun clear() {
        cache.clear()
    }
}
