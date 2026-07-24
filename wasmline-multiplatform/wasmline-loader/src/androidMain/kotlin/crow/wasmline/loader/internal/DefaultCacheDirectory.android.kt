package crow.wasmline.loader.internal

/**
 * Android implementation of [defaultCacheDirectory].
 *
 * Resolves `<applicationContext.cacheDir>/wasmline` via reflection on first call.
 * Returns `null` if not running on Android, disabling cache silently.
 */
internal actual fun defaultCacheDirectory(): String? = AndroidCacheResolver.cacheDirectory()
