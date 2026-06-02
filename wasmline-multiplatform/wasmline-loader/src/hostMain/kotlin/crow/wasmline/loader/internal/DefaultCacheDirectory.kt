package crow.wasmline.loader.internal

/**
 * Returns the platform-specific default cache directory for wasmline,
 * or `null` if caching is not supported on this platform (e.g. browser).
 */
internal expect fun defaultCacheDirectory(): String?
