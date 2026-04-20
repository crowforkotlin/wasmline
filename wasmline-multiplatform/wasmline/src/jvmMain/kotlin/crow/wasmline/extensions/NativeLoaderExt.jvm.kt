package crow.wasmline.extensions

import crow.wasmline.Wasmline
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale.US

actual fun loadNativeLibrary() {
    val osName = System.getProperty("os.name").lowercase(US)
    val osArch = normalizeArch(System.getProperty("os.arch"))
    val extension = if (osName.contains("linux")) {
        "so"
    } else if (osName.contains("mac")) {
        "dylib"
    } else if (osName.contains("windows")) {
        "dll"
    } else {
        throw IllegalStateException("Unsupported OS: $osName")
    }
    val candidateArchs = archCandidates(osArch)
    val nativeLibraryJarPath = candidateArchs
        .map { "/jni/$it/libwasmline.$extension" }
        .firstOrNull { Wasmline::class.java.getResource(it) != null }
        ?: throw IllegalStateException(
            "Unable to read native wasmline library from JAR. os.name=$osName, os.arch=${System.getProperty("os.arch")}, normalizedArch=$osArch, tried=${candidateArchs.joinToString()}"
        )
    val nativeLibraryUrl = Wasmline::class.java.getResource(nativeLibraryJarPath)!!
    val nativeLibraryFile: Path
    try {
        nativeLibraryFile = Files.createTempFile("quickjs", null)
        nativeLibraryFile.toFile().deleteOnExit()
        nativeLibraryUrl.openStream().use { nativeLibrary -> Files.copy(nativeLibrary, nativeLibraryFile, REPLACE_EXISTING) }
    } catch (e: IOException) {
        throw RuntimeException("Unable to extract native library from JAR", e)
    }
    System.load(nativeLibraryFile.toAbsolutePath().toString())
}

private fun normalizeArch(osArch: String): String = when (osArch.lowercase(US)) {
    "amd64", "x86_64" -> "x86_64"
    "arm64", "aarch64" -> "aarch64"
    else -> osArch.lowercase(US)
}

private fun archCandidates(normalizedArch: String): List<String> = when (normalizedArch) {
    "x86_64" -> listOf("x86_64", "amd64")
    "aarch64" -> listOf("aarch64", "arm64")
    else -> listOf(normalizedArch)
}

