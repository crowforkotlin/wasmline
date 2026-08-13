package crow.wasmline.web

/**
 * Byte cache bridging the async Fetch download and the sync Wasmline loader.
 *
 * `WasmlineLoader.load()` is synchronous on every platform, while the web can
 * only download artifacts asynchronously. Web callers therefore prefetch each
 * artifact first; the loader then resolves the cached bytes synchronously and
 * instantiates the module in the same call.
 *
 * Date: 2026-07-29
 * Author: crowforkotlin
 */
internal object WebWasmArtifacts {

    private val cache = mutableMapOf<String, ByteArray>()

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
    fun bytesOrNull(url: String): ByteArray? = cache[url]

    fun invalidate(url: String) {
        cache.remove(url)
    }

    fun clear() {
        cache.clear()
    }
}
