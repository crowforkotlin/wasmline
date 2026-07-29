package crow.wasmline.web

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Fetch-API based loader for `.wasm` artifacts.
 *
 * Replaces the former synchronous XHR download: artifacts are fetched
 * asynchronously and delivered either through callbacks or as a suspend
 * function for coroutine-based call sites.
 *
 * 2026-07-29
 * @author crowforkotlin
 */
internal object WebArtifactFetcher {

    fun fetch(url: String, onSuccess: (ByteArray) -> Unit, onFailure: (String) -> Unit) {
        webFetchBytes(url, onSuccess, onFailure)
    }

    /** Suspending variant; throws [WebWasmException] on any failure. */
    suspend fun fetch(url: String): ByteArray = suspendCoroutine { continuation ->
        webFetchBytes(
            url = url,
            onSuccess = { bytes -> continuation.resume(bytes) },
            onFailure = { reason ->
                continuation.resumeWithException(
                    WebWasmException("Failed to fetch wasm artifact '$url': $reason"),
                )
            },
        )
    }
}
