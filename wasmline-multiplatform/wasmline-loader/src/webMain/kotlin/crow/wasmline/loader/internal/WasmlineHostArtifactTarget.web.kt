package crow.wasmline.loader.internal

import crow.wasmline.WasmlineArtifactFormat

internal val browserCurrentHostArtifactTarget: WasmlineHostArtifactTarget =
    WasmlineHostArtifactTarget(
        operatingSystem = "browser",
        architecture = "wasm32",
        pointerWidth = 32,
        supportedArtifactFormats = setOf(WasmlineArtifactFormat.RAW_WASM),
    )
