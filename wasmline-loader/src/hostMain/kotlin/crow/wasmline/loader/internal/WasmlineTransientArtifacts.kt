package crow.wasmline.loader.internal

import crow.wasmline.WasmlineLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tracks artifact files that the loader materialized only so the runtime could open them.
 *
 * A remote artifact always reaches the runtime as a file path. When the caller
 * supplies its own [crow.wasmline.loader.WasmlineCache], the loader must not keep
 * a durable copy in its built-in cache directory, so it stages the verified bytes
 * in a transient directory instead and removes the file once the load completes.
 *
 * Date: 2026-11-11
 * Author: crowforkotlin
 */
internal object WasmlineTransientArtifacts {
    private val lock = Mutex()
    private val paths = mutableSetOf<String>()

    /** Records [path] as transient so it is deleted after the runtime finishes loading it. */
    suspend fun register(path: String) {
        lock.withLock { paths += path }
    }

    /** Deletes [path] when it was registered as transient; other paths are ignored. */
    suspend fun deleteIfTransient(path: String) {
        val registered = lock.withLock { paths.remove(path) }
        if (!registered) return
        if (hostDeleteFile(path)) return
        WasmlineLog.logger?.debug("[WasmlineTransientArtifacts] Staged artifact could not be removed: $path")
    }
}
