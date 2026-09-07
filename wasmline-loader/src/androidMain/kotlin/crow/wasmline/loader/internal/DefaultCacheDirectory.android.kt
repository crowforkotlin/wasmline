package crow.wasmline.loader.internal

/**
 * Android implementation of [defaultCacheDirectory].
 *
 * [WasmlineLoaderInitProvider] records `<cacheDir>/wasmline` during application
 * startup without retaining or passing an Android Context through loader APIs.
 */
internal actual fun defaultCacheDirectory(): String? = AndroidCacheResolver.cacheDirectory()
