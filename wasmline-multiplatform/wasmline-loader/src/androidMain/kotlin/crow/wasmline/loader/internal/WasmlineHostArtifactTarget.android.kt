package crow.wasmline.loader.internal

import crow.wasmline.WasmlineRuntime

internal actual val currentHostArtifactTarget: WasmlineHostArtifactTarget
    get() {
        val runtimeInfo = WasmlineRuntime.nativeInfo()
        return WasmlineHostArtifactTarget(
            operatingSystem = runtimeInfo?.operatingSystem ?: "android",
            architecture = runtimeInfo?.architecture ?: normalizeAndroidCpu(System.getProperty("os.arch")),
            pointerWidth = runtimeInfo?.pointerWidth ?: 64,
            supportedArtifactFormats = runtimeInfo?.supportedArtifactFormats ?: emptySet(),
            nativeRuntimeInfo = runtimeInfo,
        )
    }

private fun normalizeAndroidCpu(arch: String?): String = when (arch?.lowercase()) {
    "amd64", "x86_64" -> "x86_64"
    "arm64", "aarch64" -> "aarch64"
    else -> arch?.lowercase() ?: "unknown"
}
