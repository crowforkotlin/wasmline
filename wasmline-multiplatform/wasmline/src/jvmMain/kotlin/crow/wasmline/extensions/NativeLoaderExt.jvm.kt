package crow.wasmline.extensions

import crow.wasmline.Wasmline
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale.US

actual fun loadNativeLibrary() {
    val osName = System.getProperty("os.name").lowercase(US)
    val osArch = System.getProperty("os.arch").lowercase(US)
    val nativeLibraryJarPath = if (osName.contains("linux")) {
        "/jni/$osArch/libwasmline.so"
    } else if (osName.contains("mac")) {
        "/jni/$osArch/libwasmline.dylib"
    } else if (osName.contains("windows")) {
        "/jni/$osArch/libwasmline.dll"
    } else {
        throw IllegalStateException("Unsupported OS: $osName")
    }
    val nativeLibraryUrl = Wasmline::class.java.getResource(nativeLibraryJarPath) ?: throw IllegalStateException("Unable to read $nativeLibraryJarPath from JAR")
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