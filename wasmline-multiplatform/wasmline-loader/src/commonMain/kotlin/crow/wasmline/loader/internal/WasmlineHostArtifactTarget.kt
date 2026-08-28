package crow.wasmline.loader.internal

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineNativeRuntimeInfo

/**
 * Describes the exact host identity used by the pure artifact selector.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
internal data class WasmlineHostArtifactTarget(
    val operatingSystem: String,
    val architecture: String,
    val pointerWidth: Int,
    val supportedArtifactFormats: Set<WasmlineArtifactFormat>,
    val nativeRuntimeInfo: WasmlineNativeRuntimeInfo? = null,
)

/** Reports the host target used for artifact compatibility selection. */
internal expect val currentHostArtifactTarget: WasmlineHostArtifactTarget
