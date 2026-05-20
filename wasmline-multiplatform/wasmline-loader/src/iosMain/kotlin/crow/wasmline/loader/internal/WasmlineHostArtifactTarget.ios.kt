package crow.wasmline.loader.internal

internal actual val currentHostArtifactTarget: WasmlineHostArtifactTarget =
    WasmlineHostArtifactTarget(
        os = "ios",
        cpu = "aarch64",
        is64Bit = true,
    )
