package crow.wasmline.loader.internal

import crow.wasmline.WasmlineNativeBackend

internal data class WasmlineHostArtifactTarget(
    val os: String,
    val cpu: String,
    val is64Bit: Boolean,
    val nativeBackend: WasmlineNativeBackend? = null,
    val wasmtimeVersion: String? = null,
)

internal expect val currentHostArtifactTarget: WasmlineHostArtifactTarget
