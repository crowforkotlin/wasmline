package crow.wasmline.loader.internal

/**
 * Returns the platform-specific default cache directory for Wasmline, or `null`
 * when caching is not supported on the current host.
 *
 * Author: crowforkotlin
 * Date: 2026-08-19
 */
internal expect fun defaultCacheDirectory(): String?
