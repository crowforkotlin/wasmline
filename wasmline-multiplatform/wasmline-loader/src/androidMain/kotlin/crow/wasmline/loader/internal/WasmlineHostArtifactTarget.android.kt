package crow.wasmline.loader.internal

import android.os.Process
import crow.wasmline.WasmlineRuntime

internal actual val currentHostArtifactTarget: WasmlineHostArtifactTarget
    get() {
        val runtimeInfo = WasmlineRuntime.nativeInfo()
        return WasmlineHostArtifactTarget(
            os = "android",
            cpu = normalizeAndroidCpu(System.getProperty("os.arch")),
            is64Bit = Process.is64Bit(),
            nativeBackend = runtimeInfo?.backend,
            wasmtimeVersion = runtimeInfo?.wasmtimeVersion,
        )
    }

private fun normalizeAndroidCpu(arch: String?): String = when (arch?.lowercase()) {
    "amd64", "x86_64" -> "x86_64"
    "arm64", "aarch64" -> "aarch64"
    else -> arch?.lowercase() ?: "unknown"
}
