package crow.wasmline.loader.internal

import crow.wasmline.WasmlineCache

/**
 * File-system-backed [WasmlineCache] implementation.
 *
 * Keys are used directly as file names within [cacheDirectory].
 * Keys should be hex-encoded strings (safe for file names).
 *
 * This cache is not thread-safe for concurrent writes to the same key.
 * Concurrent reads of different keys are safe.
 */
internal class WasmlineFileCache(
    private val cacheDirectory: String,
) : WasmlineCache {

    override fun get(key: String): ByteArray? {
        return readHostFileBytes(path = resolvePath(key))
    }

    override fun put(key: String, bytes: ByteArray) {
        hostMkdirs(path = cacheDirectory)
        writeHostFileBytes(path = resolvePath(key), bytes)
    }

    override fun exists(key: String): Boolean {
        return hostPathExists(path = resolvePath(key))
    }

    private fun resolvePath(key: String): String {
        return "$cacheDirectory/$key"
    }
}
