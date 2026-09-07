package crow.wasmline.loader.internal

import android.util.Log
import crow.wasmline.WasmlineLog
import java.io.File

/** Stores only the cache path initialized by [WasmlineLoaderInitProvider]. */
internal object AndroidCacheResolver {
    @Volatile
    private var cachePath: String? = null

    @Volatile
    private var missingInitializationReported: Boolean = false

    fun initialize(applicationCacheDirectory: File) {
        cachePath = File(applicationCacheDirectory, CACHE_DIRECTORY_NAME).absolutePath
    }

    fun cacheDirectory(): String? {
        val path = cachePath
        if (path == null) reportMissingInitializationOnce()
        return path
    }

    private fun reportMissingInitializationOnce() {
        if (missingInitializationReported) return
        synchronized(this) {
            if (missingInitializationReported) return
            missingInitializationReported = true
            val message =
                "Wasmline Android cache initialization is unavailable. " +
                    "Ensure WasmlineLoaderInitProvider is present in the merged manifest."
            val logger = WasmlineLog.logger
            if (logger != null) {
                logger.warn("$P $message")
            } else {
                Log.w(LOG_TAG, message)
            }
        }
    }

    private const val P: String = "[AndroidCacheResolver]"
    private const val LOG_TAG: String = "WasmlineLoader"
    private const val CACHE_DIRECTORY_NAME: String = "wasmline"
}
