package crow.wasmline.loader.internal

internal data class WasmlineHostArtifactTarget(val os: String, val cpu: String, val is64Bit: Boolean)

internal expect val currentHostArtifactTarget: WasmlineHostArtifactTarget
