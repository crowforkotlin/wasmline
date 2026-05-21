@file:Suppress("SpellCheckingInspection")

package crow.wasmline.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.unique
import com.github.ajalt.clikt.parameters.types.file
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/**
 * Compile task
 *
 * 2026/1/20 00:16
 * @author crowforkotlin
 * @formatter:on
 */
class Download : CliktCommand(name = "download") {

    private val client = HttpClient(CIO)

    private val downloadVersions by option("-v", "--versions")
        .multiple()
        .unique()
        .help("default : lastest version, e.g : v41.0.1,v41.0.2")

    private val archOption by option("-a", "--arch")
        .help("default : current os arch")

    private val outputDir by option("-o", "--output")
        .file(canBeFile = false, canBeDir = true)
        .default(File("build/wasmline/wasmtime"))
        .help("default : [./build/wasmline/wasmtime]")

    private val forceDownload by option("-f", "--force")
        .flag(default = false)
        .help("Force redownload even if already exists")

    /**
     * Entry point for the download command
     *
     * 2026/2/11 19:20
     * @author crowforkotlin
     * @formatter:on
     */
    override fun run() = runBlocking {
        val targetVersions = downloadVersions.flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("latest") }

        val currentPlatform = archOption ?: detectPlatform()
        var hasFailure = false
        withContext(Dispatchers.IO) {
            if (!outputDir.exists()) outputDir.mkdirs()
            targetVersions.forEach { version ->
                runCatching {
                    processDownload(version, currentPlatform)
                }.onFailure { throwable ->
                    hasFailure = true
                    echo("Error: ${throwable.message}", err = true)
                }
            }
        }
        if (hasFailure) {
            throw ProgramResult(1)
        }
    }

    /**
     * Process the download and extraction for a specific version
     *
     * 2026/2/11 19:20
     * @author crowforkotlin
     * @formatter:on
     */
    private suspend fun processDownload(version: String, platform: String) {
        val releaseJson = resolveRelease(version)
        val assets = releaseJson["assets"]?.jsonArray
            ?: throw IllegalStateException("Release for '$version' did not contain any assets")

        val filteredAssets = assets.map { it.jsonObject }.filter { asset ->
            val name = asset["name"]?.jsonPrimitive?.content ?: ""
            val isNotCApi = !name.contains("c-api")
            val matchesPlatform = platform == "all" || name.contains(platform)
            isNotCApi && matchesPlatform
        }

        if (filteredAssets.isEmpty()) {
            throw IllegalStateException("No wasmtime assets matched version '$version' for platform '$platform'")
        }

        filteredAssets.forEach { asset ->
            val fileName = asset["name"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Missing asset name in release metadata")
            val downloadUrl = asset["browser_download_url"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Missing download url for asset '$fileName'")
            val folderName = fileName.removeSuffix(".tar.xz").removeSuffix(".zip")
            val targetFolder = File(outputDir, folderName)
            val successFile = File(targetFolder, ".success")
            if (successFile.exists() && !forceDownload) {
                println("Skipping: $fileName (Already exists and complete)")
                return@forEach
            }
            if (targetFolder.exists()) {
                targetFolder.deleteRecursively()
            }
            targetFolder.mkdirs()

            val tempFile = File(outputDir, fileName)
            println("Downloading: $fileName")

            val response = client.get(downloadUrl)
            if (!response.status.isSuccess()) {
                targetFolder.deleteRecursively()
                throw IllegalStateException("Download failed for '$fileName' with HTTP ${response.status.value}")
            }
            val totalBytes = response.contentLength() ?: 0L
            val channel = response.bodyAsChannel()

            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(8192)
                var downloadedBytes = 0L
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    if (totalBytes > 0) printProgress(downloadedBytes, totalBytes)
                }
            }
            println()

            try {
                when {
                    fileName.endsWith(".tar.xz") -> {
                        println("Extracting TAR.XZ: $fileName...")
                        extractTarXz(tempFile, outputDir)
                    }
                    fileName.endsWith(".zip") -> {
                        println("Extracting ZIP: $fileName...")
                        extractZip(tempFile, outputDir)
                    }
                    else -> {
                        targetFolder.deleteRecursively()
                        throw IllegalStateException("Unsupported archive type for '$fileName'")
                    }
                }
                successFile.writeText("version=$version\nplatform=$platform\nurl=$downloadUrl")
            } catch (e: Exception) {
                println("Extraction failed: ${e.message}")
                targetFolder.deleteRecursively()
                throw e
            } finally {
                tempFile.delete()
            }
        }
    }

    private suspend fun resolveRelease(version: String): JsonObject {
        val urls = if (version == "latest") {
            listOf(REPOSITORY)
        } else {
            candidateTags(version).map { "$BASE_URL/tags/$it" }
        }

        val failures = mutableListOf<String>()
        urls.forEach { url ->
            val response = client.get(url)
            if (!response.status.isSuccess()) {
                failures += "$url -> HTTP ${response.status.value}"
                return@forEach
            }
            val body = response.bodyAsText()
            val releaseJson = Json.decodeFromString<JsonObject>(body)
            if (releaseJson["assets"] != null) {
                return releaseJson
            }
            failures += "$url -> missing assets"
        }
        throw IllegalStateException(
            "Unable to resolve wasmtime release for '$version'. Tried: ${failures.joinToString("; ")}"
        )
    }

    private fun candidateTags(version: String): List<String> {
        val raw = version.trim()
        val base = raw.removePrefix("release-").removePrefix("v")
        return listOf(
            raw,
            "v$base",
            "release-$raw",
            "release-v$base"
        ).distinct()
    }

    /**
     * Extract ZIP archives to target directory
     *
     * 2026/2/11 19:20
     * @author crowforkotlin
     * @formatter:on
     */
    private fun extractZip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outputFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile.mkdirs()
                    Files.copy(zis, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Extract TAR.XZ archives to target directory
     *
     * 2026/2/11 19:20
     * @author crowforkotlin
     * @formatter:on
     */
    private fun extractTarXz(archive: File, targetDir: File) {
        archive.inputStream().use { fis ->
            XZCompressorInputStream(fis).use { xzis ->
                TarArchiveInputStream(xzis).use { tais ->
                    var entry = tais.nextEntry
                    while (entry != null) {
                        val outputFile = File(targetDir, entry.name)
                        if (entry.isDirectory) {
                            outputFile.mkdirs()
                        } else {
                            outputFile.parentFile.mkdirs()
                            Files.copy(tais, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            if (entry.mode != 0) outputFile.setExecutable(true)
                        }
                        entry = tais.nextEntry
                    }
                }
            }
        }
    }

    /**
     * Print visual progress bar to the console
     *
     * 2026/2/11 19:20
     * @author crowforkotlin
     * @formatter:on
     */
    private fun printProgress(current: Long, total: Long) {
        val width = 40
        val progress = (current.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
        val filled = (progress * width).toInt()
        val percent = (progress * 100).toInt()
        val bar = StringBuilder("\r[")
        for (i in 0 until width) {
            when {
                i < filled -> bar.append("=")
                i == filled -> bar.append(">")
                else -> bar.append(" ")
            }
        }
        bar.append("] $percent% (${formatSize(current)}/${formatSize(total)})")
        print(bar.toString())
        System.out.flush()
    }

    /**
     * Format bytes into strings
     *
     * 2026/2/11 19:20
     * @author crowforkotlin
     * @formatter:on
     */
    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    /**
     * Detect the current OS and architecture
     *
     * 2026/2/11 19:20
     * @author crowforkotlin
     * @formatter:on
     */
    private fun detectPlatform(): String {
        return DownloadPlatformDetector.detectPlatform()
    }

    companion object {
        const val BASE_URL = "https://api.github.com/repos/crowforkotlin/wasmtime/releases"
        const val REPOSITORY = "$BASE_URL/latest"
    }
}

internal object DownloadPlatformDetector {

    fun detectPlatform(): String {
        val osName = System.getProperty("os.name")
        val osArch = System.getProperty("os.arch")
        val normalizedOs = normalizeOs(osName)
        val macHardwareArm64 = if (normalizedOs == "macos" && normalizeArch(osArch) == "x86_64") {
            detectMacHardwareArm64()
        } else {
            null
        }
        return detectPlatform(osName = osName, osArch = osArch, macHardwareArm64 = macHardwareArm64)
    }

    internal fun detectPlatform(osName: String, osArch: String, macHardwareArm64: Boolean? = null): String {
        val normalizedOs = normalizeOs(osName)
        val normalizedArch = when {
            normalizedOs == "macos" && normalizeArch(osArch) == "x86_64" && macHardwareArm64 == true -> "aarch64"
            else -> normalizeArch(osArch)
        }
        return "$normalizedArch-$normalizedOs"
    }

    internal fun normalizeOs(osName: String): String {
        val normalizedName = osName.lowercase()
        return when {
            normalizedName.contains("win") -> "windows"
            normalizedName.contains("mac") -> "macos"
            normalizedName.contains("linux") -> "linux"
            normalizedName.contains("android") -> "android"
            else -> "unknown"
        }
    }

    internal fun normalizeArch(osArch: String): String {
        val normalizedName = osArch.lowercase()
        return when {
            normalizedName.contains("amd64") || normalizedName.contains("x86_64") -> "x86_64"
            normalizedName.contains("aarch64") || normalizedName.contains("arm64") -> "aarch64"
            else -> normalizedName
        }
    }

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
