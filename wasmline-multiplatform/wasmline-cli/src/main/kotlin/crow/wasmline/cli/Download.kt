@file:Suppress("SpellCheckingInspection")

package crow.wasmline.cli

import com.github.ajalt.clikt.core.CliktCommand
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
        withContext(Dispatchers.IO) {
            if (!outputDir.exists()) outputDir.mkdirs()
            targetVersions.forEach { version ->
                processDownload(version, currentPlatform)
            }
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
        try {
            val url = if (version == "latest") REPOSITORY else "$BASE_URL/tags/$version"
            val responseText = client.get(url).bodyAsText()
            val releaseJson = Json.decodeFromString<JsonObject>(responseText)
            val assets = releaseJson["assets"]?.jsonArray ?: return

            val filteredAssets = assets.map { it.jsonObject }.filter { asset ->
                val name = asset["name"]?.jsonPrimitive?.content ?: ""
                val isNotCApi = !name.contains("c-api")
                val matchesPlatform = platform == "all" || name.contains(platform)
                isNotCApi && matchesPlatform
            }

            filteredAssets.forEach { asset ->
                val fileName = asset["name"]?.jsonPrimitive?.content ?: return@forEach
                val downloadUrl = asset["browser_download_url"]?.jsonPrimitive?.content ?: return@forEach
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
                    }
                    // 解压成功后，写入成功标记
                    successFile.writeText("version=$version\nplatform=$platform\nurl=$downloadUrl")
                } catch (e: Exception) {
                    println("Extraction failed: ${e.message}")
                    targetFolder.deleteRecursively() // 失败了就清理掉
                    throw e
                } finally {
                    tempFile.delete() // 无论成功失败都删掉压缩包
                }
            }
        } catch (e: Exception) {
            println("\nError: ${e.message}")
        }
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
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val normalizedOs = when {
            os.contains("win") -> "windows"
            os.contains("mac") -> "macos"
            os.contains("linux") -> "linux"
            os.contains("android") -> "android"
            else -> "unknown"
        }
        val normalizedArch = when {
            arch.contains("amd64") || arch.contains("x86_64") -> "x86_64"
            arch.contains("aarch64") || arch.contains("arm64") -> "aarch64"
            else -> arch
        }
        return "$normalizedArch-$normalizedOs"
    }

    companion object {
        const val BASE_URL = "https://api.github.com/repos/crowforkotlin/wasmtime/releases"
        const val REPOSITORY = "$BASE_URL/latest"
    }
}