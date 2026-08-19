package crow.wasmline.loader.internal

import crow.wasmline.WasmlineRuntime

/**
 * Reports the linked runtime identity to the Native artifact selector.
 *
 * Author: crowforkotlin
 * Date: 2026-08-19
 */
internal actual val currentHostArtifactTarget: WasmlineHostArtifactTarget
    get() {
        val runtimeInfo = WasmlineRuntime.nativeInfo()
        return WasmlineHostArtifactTarget(
            os = runtimeInfo?.targetOs ?: "unknown",
            cpu = runtimeInfo?.targetCpu ?: "unknown",
            is64Bit = runtimeInfo?.is64Bit ?: false,
            nativeBackend = runtimeInfo?.backend,
            wasmtimeVersion = runtimeInfo?.wasmtimeVersion,
        )
    }
