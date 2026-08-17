package crow.wasmline.loader.internal

import android.annotation.SuppressLint
import java.io.File

private const val UNINITIALIZED = "\u0000__uninitialized__"

/**
 * Resolves the Android cache directory via reflection, storing only the path string.
 *
 * On Android, `ActivityThread.currentApplication` is used to obtain the application
 * context once, extract `cacheDir`, and discard the reference immediately.
 * Only the resolved path [String] is retained — no context is held.
 *
 * If reflection fails (e.g. non-Android JVM, or an unsupported runtime),
 * [cacheDirectory] returns `null`.
 */
internal object AndroidCacheResolver {

    @Volatile
    private var cachePath: String? = UNINITIALIZED

    fun cacheDirectory(): String? {
        val cached = cachePath
        if (cached != UNINITIALIZED) return cached
        return resolveOnce()
    }

    @Synchronized
    private fun resolveOnce(): String? {
        // Double-check after acquiring lock
        val cached = cachePath
        if (cached != UNINITIALIZED) return cached

        val path = resolveAndroidCacheDir()
        cachePath = path
        return path
    }

    @SuppressLint("PrivateApi")
    private fun resolveAndroidCacheDir(): String? {
        return runCatching {
            val currentApp = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.app.Application
                ?: return null
            File(currentApp.cacheDir, "wasmline").absolutePath
        }.getOrNull()
    }
}
