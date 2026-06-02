package crow.wasmline.loader.internal

internal actual fun defaultCacheDirectory(): String? {
    val userHome = System.getProperty("user.home") ?: return null
    return "$userHome/.wasmline/cache"
}
