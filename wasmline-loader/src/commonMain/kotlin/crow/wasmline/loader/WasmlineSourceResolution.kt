package crow.wasmline.loader

import crow.wasmline.WasmlineLoadState

/**
 * Result of a source resolution step in the loader chain.
 *
 * - [ContinueWith]: Resolution produced a new source to continue loading.
 * - [Complete]: Resolution produced a terminal load state (success or failure).
 */
sealed interface WasmlineSourceResolution {
    data class ContinueWith(val source: WasmlineSource) : WasmlineSourceResolution
    data class Complete(val state: WasmlineLoadState) : WasmlineSourceResolution
}
