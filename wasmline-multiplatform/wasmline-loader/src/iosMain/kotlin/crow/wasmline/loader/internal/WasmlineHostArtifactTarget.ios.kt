package crow.wasmline.loader.internal

import crow.wasmline.WasmlineRuntime

internal actual val currentHostArtifactTarget: WasmlineHostArtifactTarget
    get() {
        val runtimeInfo = WasmlineRuntime.nativeInfo()
        return WasmlineHostArtifactTarget(
            os = "ios",
            cpu = "aarch64",
            is64Bit = true,
            nativeBackend = runtimeInfo?.backend,
            wasmtimeVersion = runtimeInfo?.wasmtimeVersion,
        )
    }
