package crow.wasmline.loader.internal

import crow.wasmline.WasmlineRuntime

internal actual val currentHostArtifactTarget: WasmlineHostArtifactTarget
    get() {
        val runtimeInfo = WasmlineRuntime.nativeInfo()
        return WasmlineHostArtifactTarget(
            operatingSystem = runtimeInfo?.operatingSystem ?: normalizeHostOs(System.getProperty("os.name")),
            architecture = runtimeInfo?.architecture ?: normalizeHostCpu(System.getProperty("os.arch")),
            pointerWidth = runtimeInfo?.pointerWidth ?: pointerWidth(System.getProperty("os.arch")),
            supportedArtifactFormats = runtimeInfo?.supportedArtifactFormats ?: emptySet(),
            nativeRuntimeInfo = runtimeInfo,
        )
    }

private fun normalizeHostOs(osName: String?): String {
    val value = osName?.lowercase() ?: return "unknown"
    return when {
        "android" in value -> "android"
        "mac" in value -> "macos"
        "win" in value -> "windows"
        "linux" in value -> "linux"
        else -> value
    }
}

private fun normalizeHostCpu(arch: String?): String = when (arch?.lowercase()) {
    "amd64", "x86_64" -> "x86_64"
    "arm64", "aarch64" -> "aarch64"
    else -> arch?.lowercase() ?: "unknown"
}

private fun pointerWidth(arch: String?): Int {
    val value = arch?.lowercase() ?: return 64
    return if ("64" in value || "aarch64" in value || "arm64" in value) 64 else 32
}
