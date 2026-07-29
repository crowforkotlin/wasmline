package crow.wasmline

import crow.wasmline.web.WebWasmArtifacts

/**
 * Web-specific entry points complementing `WasmlineLoader`.
 *
 * Browsers and Node hosts can only download artifacts asynchronously via the
 * Fetch API, while `WasmlineLoader.load()` is synchronous on every platform.
 * Web applications therefore prefetch each artifact once; the subsequent
 * load call instantiates the module from the cached bytes:
 *
 * ```kotlin
 * WasmlineWeb.prefetch("plugin.wasm")           // suspend variant
 * val result = WasmlineLoader.load("plugin.wasm")
 * ```
 *
 * 2026-07-29
 * @author crowforkotlin
 */
object WasmlineWeb {

    /**
     * Downloads and caches the artifact at [url].
     *
     * Exactly one callback is invoked: [onReady] once the bytes are cached,
     * or [onFailure] with a human-readable reason.
     */
    fun prefetch(url: String, onReady: () -> Unit, onFailure: (String) -> Unit) {
        WebWasmArtifacts.prefetch(url, onReady, onFailure)
    }

    /** Suspending variant of [prefetch]; throws on download failure. */
    suspend fun prefetch(url: String) {
        WebWasmArtifacts.prefetch(url)
    }

    /** Drops the cached bytes for [url], forcing a re-download next time. */
    fun invalidate(url: String) {
        WebWasmArtifacts.invalidate(url)
    }
}
