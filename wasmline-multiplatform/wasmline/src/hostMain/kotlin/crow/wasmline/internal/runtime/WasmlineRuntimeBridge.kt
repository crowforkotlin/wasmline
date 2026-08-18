package crow.wasmline.internal.runtime

import crow.wasmline.InternalWasmlineRuntimeApi
import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineLoadState
import crow.wasmline.platformWasmlineLoadArtifact

/** Binary bridge used by the separately published `wasmline-loader` module. */
@InternalWasmlineRuntimeApi
object WasmlineRuntimeBridge {
    fun loadResolvedArtifact(descriptor: WasmlineArtifactDescriptor, config: WasmlineConfig): WasmlineLoadState =
        platformWasmlineLoadArtifact(descriptor, config)
}
