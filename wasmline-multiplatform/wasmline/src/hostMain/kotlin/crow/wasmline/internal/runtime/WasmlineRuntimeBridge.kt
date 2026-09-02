package crow.wasmline.internal.runtime

import crow.wasmline.InternalWasmlineRuntimeApi
import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineLoadState
import crow.wasmline.platformWasmlineLoadArtifact

/**
 * Provides the loader with the platform artifact-loading entry point.
 *
 * Date: 2026-09-02
 * Author: crowforkotlin
 */
@InternalWasmlineRuntimeApi
object WasmlineRuntimeBridge {
    fun loadResolvedArtifact(descriptor: WasmlineArtifactDescriptor, config: WasmlineConfig): WasmlineLoadState =
        platformWasmlineLoadArtifact(descriptor, config)
}
