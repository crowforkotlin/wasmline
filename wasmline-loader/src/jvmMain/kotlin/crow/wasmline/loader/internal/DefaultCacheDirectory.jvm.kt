package crow.wasmline.loader.internal

/**
 * JVM implementation of [defaultCacheDirectory].
 *
 * Detects the operating system and returns the platform-conventional cache path:
 * - Linux: `$XDG_CACHE_HOME/wasmline` or `~/.cache/wasmline`
 * - macOS: `~/Library/Caches/wasmline`
 * - Windows: `%LOCALAPPDATA%/wasmline/caches`
 * - Unknown OS: `~/.wasmline/cache`
 */
internal actual fun defaultCacheDirectory(): String? {
    val os = System.getProperty("os.name")?.lowercase() ?: return fallbackCacheDir()
    return when {
        "mac" in os || "darwin" in os -> macCacheDir()
        "win" in os -> windowsCacheDir()
        "linux" in os -> linuxCacheDir()
        else -> fallbackCacheDir()
    }
}

private fun linuxCacheDir(): String? {
    val xdg = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
    if (xdg != null) return "$xdg/wasmline"
    val home = System.getProperty("user.home") ?: return null
    return "$home/.cache/wasmline"
}

private fun macCacheDir(): String? {
    val home = System.getProperty("user.home") ?: return null
    return "$home/Library/Caches/wasmline"
}

private fun windowsCacheDir(): String? {
    val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
    if (localAppData != null) return "$localAppData/wasmline/caches"
    val home = System.getProperty("user.home") ?: return null
    return "$home/AppData/Local/wasmline/caches"
}

private fun fallbackCacheDir(): String? {
    val home = System.getProperty("user.home") ?: return null
    return "$home/.wasmline/cache"
}
