package crow.wasmline.plugin.core.util

import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import java.io.File

/**
 * Detects the platform name used by Wasmtime downloads.
 *
 * Date: 2026-07-31
 * Author: crowforkotlin
 */

@InternalWasmlineToolingApi
object PlatformDetector {

    /** Returns the platform name for the current operating system and architecture. */
    fun detectPlatform(): String {
        val osName = System.getProperty("os.name")
        val osArch = System.getProperty("os.arch")
        val normalizedOs = normalizeOs(osName)
        val macHardwareArm64 = if (normalizedOs == "macos" && normalizeArch(osArch) == "x86_64") {
            detectMacHardwareArm64()
        } else {
            null
        }
        return detectPlatform(
            osName = osName,
            osArch = osArch,
            macHardwareArm64 = macHardwareArm64,
        )
    }

    /** Returns the platform name from the supplied system properties. */
    fun detectPlatform(osName: String, osArch: String, macHardwareArm64: Boolean? = null): String {
        val normalizedOs = normalizeOs(osName)
        val normalizedArch = when {
            normalizedOs == "macos" && normalizeArch(osArch) == "x86_64" && macHardwareArm64 == true -> "aarch64"
            else -> normalizeArch(osArch)
        }
        return "$normalizedArch-$normalizedOs"
    }

    /** Converts an operating system name to the Wasmtime name. */
    fun normalizeOs(osName: String): String {
        val normalizedName = osName.lowercase()
        return when {
            normalizedName.contains("win") -> "windows"
            normalizedName.contains("mac") -> "macos"
            normalizedName.contains("linux") -> "linux"
            normalizedName.contains("android") -> "android"
            else -> "unknown"
        }
    }

    /** Converts an architecture name to the Wasmtime name. */
    fun normalizeArch(osArch: String): String {
        val normalizedName = osArch.lowercase()
        return when {
            normalizedName.contains("amd64") || normalizedName.contains("x86_64") -> "x86_64"
            normalizedName.contains("aarch64") || normalizedName.contains("arm64") -> "aarch64"
            else -> normalizedName
        }
    }

    /** Detects Apple Silicon when the current Java runtime uses x86_64. */
    private fun detectMacHardwareArm64(): Boolean? {
        val sysctl = File("/usr/sbin/sysctl").takeIf(File::exists)?.absolutePath ?: "sysctl"
        return runCatching {
            val process = ProcessBuilder(sysctl, "-in", "hw.optional.arm64")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            if (process.waitFor() == 0) output == "1" else null
        }.getOrNull()
    }
}
