package crow.wasmline.loader.internal

import crow.wasmline.WasmlineRuntime

/** Reports the linked runtime identity to the Native artifact selector. */
internal actual val currentHostArtifactTarget: WasmlineHostArtifactTarget
    get() {
        val runtimeInfo = WasmlineRuntime.nativeInfo()
        return WasmlineHostArtifactTarget(
            operatingSystem = runtimeInfo?.operatingSystem ?: "unknown",
            architecture = runtimeInfo?.architecture ?: "unknown",
            pointerWidth = runtimeInfo?.pointerWidth ?: 64,
            supportedArtifactFormats = runtimeInfo?.supportedArtifactFormats ?: emptySet(),
            nativeRuntimeInfo = runtimeInfo,
        )
    }
