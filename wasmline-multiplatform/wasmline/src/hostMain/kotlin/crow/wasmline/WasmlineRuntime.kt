package crow.wasmline

/**
 * Process-wide Wasmline runtime lifecycle.
 *
 * Artifact resolution and loading belong to `WasmlineLoader`; a loaded [Wasmline]
 * remains an independently closeable handle. Most applications can load lazily
 * without calling [preload] or [warmUp].
 */
object WasmlineRuntime {
    /**
     * Loads the platform runtime bridge without creating a Wasmtime engine.
     *
     * This is useful only when an application wants native linking failures or
     * bridge startup cost to occur before its first load. It is a no-op on
     * platforms whose runtime bridge is already linked statically.
     */
    fun preload() {
        platformWasmlinePreload()
    }

    /**
     * Eagerly creates [engine] without loading an artifact.
     *
     * A different engine is selected only when no loaded artifacts are alive.
     * Unsupported engines and destructive switches fail instead of silently
     * falling back or invalidating existing [Wasmline] handles.
     */
    fun warmUp(engine: WasmlineEngineKind) {
        platformWasmlineWarmUp(engine)
    }

    /** Returns immutable native runtime information, or `null` in a browser. */
    fun nativeInfo(): WasmlineNativeRuntimeInfo? = platformWasmlineNativeRuntimeInfo()

    /**
     * Releases all process-wide engines, loaded artifacts, and cached sessions.
     *
     * This operation invalidates every live [Wasmline] handle. It is idempotent;
     * a later load can initialize the runtime again.
     */
    fun shutdown() {
        platformWasmlineShutdown()
    }
}

internal expect fun platformWasmlinePreload()

internal expect fun platformWasmlineShutdown()

internal expect fun platformWasmlineWarmUp(engine: WasmlineEngineKind)

internal expect fun platformWasmlineNativeRuntimeInfo(): WasmlineNativeRuntimeInfo?

internal expect fun platformWasmlineRuntimeCapabilities(): WasmlineRuntimeCapabilities

internal expect fun platformWasmlineLoadArtifact(filepath: String, config: WasmlineConfig): WasmlineLoadState

internal expect fun platformWasmlineLoadArtifact(descriptor: WasmlineArtifactDescriptor, config: WasmlineConfig): WasmlineLoadState
