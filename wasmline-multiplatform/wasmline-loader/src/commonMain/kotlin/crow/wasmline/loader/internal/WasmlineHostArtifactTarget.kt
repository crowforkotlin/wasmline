package crow.wasmline.loader.internal

import crow.wasmline.WasmlineNativeBackend

internal data class WasmlineHostArtifactTarget(
    val os: String,
    val cpu: String,
    val is64Bit: Boolean,
    val nativeBackend: WasmlineNativeBackend? = null,
    val wasmtimeVersion: String? = null,
)

/**
 * Reports the host target used for artifact compatibility selection.
 *
 * Author: crowforkotlin
 * Date: 2026-08-19
 */
internal expect val currentHostArtifactTarget: WasmlineHostArtifactTarget
