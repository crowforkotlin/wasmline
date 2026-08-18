package crow.wasmline.extensions

/** Ensures that the current platform's runtime bridge is linked and ready. */
internal expect fun ensureNativeRuntimeLoaded()
