package crow.wasmline.loader.internal

internal actual val currentHostArtifactTarget: WasmlineHostArtifactTarget
    get() = WasmlineHostArtifactTarget(
        os = normalizeHostOs(System.getProperty("os.name")),
        cpu = normalizeHostCpu(System.getProperty("os.arch")),
        is64Bit = is64BitArch(System.getProperty("os.arch")),
    )

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

private fun normalizeHostCpu(arch: String?): String {
    return when (arch?.lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "arm64", "aarch64" -> "aarch64"
        else -> arch?.lowercase() ?: "unknown"
    }
}

private fun is64BitArch(arch: String?): Boolean {
    val value = arch?.lowercase() ?: return true
    return "64" in value || "aarch64" in value || "arm64" in value
}
