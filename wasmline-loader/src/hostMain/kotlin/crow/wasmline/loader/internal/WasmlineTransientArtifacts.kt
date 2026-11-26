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
    private val paths = mutableMapOf<String, String>()

    /** Records [path] and its staging [directoryPath] so both are deleted after the load. */
    suspend fun register(path: String, directoryPath: String) {
        lock.withLock { paths[path] = directoryPath }
    }

    /** Deletes [path] and its empty staging directory when it was registered; other paths are ignored. */
    suspend fun deleteIfTransient(path: String) {
        val directoryPath = lock.withLock { paths.remove(path) } ?: return
        if (hostDeleteFile(path)) {
            hostDeleteFile(directoryPath)
            return
        }
        WasmlineLog.logger?.debug("[WasmlineTransientArtifacts] Staged artifact could not be removed: $path")
    }
}
