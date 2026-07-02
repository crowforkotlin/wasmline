package crow.wasmline.sample

/**
 * Platform-specific asset refresher for "Fresh Mode".
 *
 * When Fresh Mode is enabled, [refresh] is called before loading
 * to ensure the on-disk Wasm artifact is up-to-date (e.g. re-copied
 * from APK assets on Android, re-extracted from classpath on Desktop,
 * or cache-busted on Web).
 *
 * Returns the (possibly updated) path to load from.
 */
interface AssetRefresher {
    suspend fun refresh(wasmPath: String): String
}

/**
 * No-op implementation that returns the path unchanged.
 * Used on platforms where caching is not an issue (e.g. iOS bundle paths
 * change on every install).
 */
object NoOpAssetRefresher : AssetRefresher {
    override suspend fun refresh(wasmPath: String): String = wasmPath
}
