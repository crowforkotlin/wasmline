package crow.wasmline.loader.internal

internal val browserCurrentHostArtifactTarget: WasmlineHostArtifactTarget =
    WasmlineHostArtifactTarget(
        os = "browser",
        cpu = "wasmjs",
        is64Bit = true,
    )
