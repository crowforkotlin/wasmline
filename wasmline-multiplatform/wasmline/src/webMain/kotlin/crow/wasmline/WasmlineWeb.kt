package crow.wasmline

import crow.wasmline.web.WebWasmArtifacts

/**
 * Web-specific entry points complementing `WasmlineLoader`.
 *
 * Web applications prefetch each raw `.wasm` artifact through the Fetch API;
 * the subsequent loader call instantiates the module from the cached bytes.
 * Download and loading are separate suspend operations because the verified
 * remote-package pipeline is not the browser raw-Wasm path:
 *
 * ```kotlin
 * WasmlineWeb.prefetch("plugin.wasm")
 * val result = WasmlineLoader.load("plugin.wasm")
 * ```
 *
 * Date: 2026-07-29
 * Author: crowforkotlin
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
