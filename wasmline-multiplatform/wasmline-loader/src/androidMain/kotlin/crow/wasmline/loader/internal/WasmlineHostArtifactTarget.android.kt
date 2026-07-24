package crow.wasmline.loader.internal

import android.os.Process

internal actual val currentHostArtifactTarget: WasmlineHostArtifactTarget
    get() = WasmlineHostArtifactTarget(
        os = "android",
        cpu = normalizeAndroidCpu(System.getProperty("os.arch")),
        is64Bit = Process.is64Bit(),
    )

private fun normalizeAndroidCpu(arch: String?): String = when (arch?.lowercase()) {
    "amd64", "x86_64" -> "x86_64"
    "arm64", "aarch64" -> "aarch64"
    else -> arch?.lowercase() ?: "unknown"
}
