package crow.wasmline.plugin.core.download

import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import crow.wasmline.plugin.core.util.PlatformDetector
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Selects either the runtime-only asset or the full build-time Wasmtime CLI. */
enum class WasmtimeDistribution {
    MINIMAL,
    FULL,
}

internal fun matchesWasmtimeDistributionAsset(assetName: String, platform: String, distribution: WasmtimeDistribution): Boolean {
    val name = assetName.lowercase()
    val isMinimal = name.contains("-min")
    return !name.contains("c-api") &&
        !name.contains("-pulley") &&
        (platform == "all" || name.contains(platform.lowercase())) &&
        when (distribution) {
            WasmtimeDistribution.MINIMAL -> isMinimal
            WasmtimeDistribution.FULL -> !isMinimal
        }
}

/**
 * Downloads and extracts Wasmtime releases.
 *
 * Date: 2026-07-31
 * Author: crowforkotlin
 */
class WasmtimeDownloader(private val httpClient: HttpClient = HttpClient(CIO)) : Closeable {

    companion object {
        private const val BASE_URL = "https://api.github.com/repos/crowforkotlin/wasmtime/releases"
        private const val REPOSITORY = "$BASE_URL/latest"
    }

    /** Downloads the requested Wasmtime release and returns its extracted directory. */
    suspend fun download(
        githubToken: String? = null,
        version: String = "latest",
        platform: String = PlatformDetector.detectPlatform(),
        distribution: WasmtimeDistribution = WasmtimeDistribution.MINIMAL,
        outputDir: File = File("build/wasmline/wasmtime"),
        force: Boolean = false,
    ): File = withContext(Dispatchers.IO) {
        println("Downloading ${distribution.name.lowercase()} wasmtime v$version for $platform...")

        // Resolve release information
        val releaseJson = resolveRelease(version, githubToken)
        val assets = releaseJson["assets"]?.jsonArray ?: throw IllegalStateException(
            "Release for '$version' did not contain any assets",
        )

        // Filter assets for the correct platform
        val filteredAssets = assets.map { it.jsonObject }.filter { asset ->
            val name = asset["name"]?.jsonPrimitive?.content ?: ""
            matchesWasmtimeDistributionAsset(name, platform, distribution)
        }

        if (filteredAssets.isEmpty()) {
            throw IllegalStateException(
                "No wasmtime assets matched version '$version' for platform '$platform'",
            )
        }

        val downloadedFolders = mutableListOf<File>()
        var hasFailure = false
        filteredAssets.forEach { asset ->
            downloadAndExtract(asset, outputDir, force, githubToken, version, platform, distribution)
                .onSuccess(downloadedFolders::add)
                .onFailure { error ->
                    hasFailure = true
                    println("Error: ${error.message}")
                }
        }

        if (hasFailure) {
            throw IllegalStateException("Download failed for one or more assets")
        }

        downloadedFolders.firstOrNull()
            ?: throw IllegalStateException("No wasmtime folder found after download")
    }

    override fun close() {
        httpClient.close()
    }

    /** Downloads and extracts a release asset. */
    private suspend fun downloadAndExtract(
        asset: JsonObject,
        outputDir: File,
        force: Boolean,
        githubToken: String? = null,
        version: String = "latest",
        platform: String = PlatformDetector.detectPlatform(),
        distribution: WasmtimeDistribution = WasmtimeDistribution.MINIMAL,
    ): Result<File> = runCatching {
        val fileName = asset["name"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Missing asset name in release metadata")
        val downloadUrl = asset["browser_download_url"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Missing download url for asset '$fileName'")

        val folderName = fileName
            .removeSuffix(".tar.xz")
            .removeSuffix(".tar.gz")
            .removeSuffix(".zip")
        val targetFolder = File(outputDir, folderName)
        val successFile = File(targetFolder, ".success")

        // Keep an existing verified download unless the caller requests a replacement.
        if (successFile.exists() && !force) {
            println("Skipping: $fileName (Already exists)")
            return@runCatching targetFolder
        }

        if (targetFolder.exists()) {
            targetFolder.deleteRecursively()
        }
        targetFolder.mkdirs()

        // Download the archive before extraction.
        val tempFile = File(outputDir, fileName)
        if (tempFile.isDirectory) tempFile.deleteRecursively()

        println("Downloading: $fileName")
        downloadFile(downloadUrl, tempFile, githubToken)

        // Select the extractor from the archive suffix.
        when {
            fileName.endsWith(".tar.xz") -> {
                println("Extracting TAR.XZ: $fileName...")
                extractTarXz(tempFile, outputDir)
            }

            fileName.endsWith(".tar.gz") -> {
                println("Extracting TAR.GZ: $fileName...")
                extractTarGz(tempFile, outputDir)
            }

            fileName.endsWith(".zip") -> {
                println("Extracting ZIP: $fileName...")
                extractZip(tempFile, outputDir)
            }

            else -> {
                throw IllegalStateException("Unsupported archive type for '$fileName'")
            }
        }

        // Confirm that the extracted files contain Wasmtime.
        val wasmtimeExecutable = findExtractedWasmtimeExecutable(targetFolder, distribution)
            ?: throw IllegalStateException(
                "Downloaded asset '$fileName' did not contain wasmtime executable",
            )

        // Record the verified download details.
        successFile.writeText("version=$version\nplatform=$platform\nurl=$downloadUrl")

        println("Successfully downloaded and extracted: $fileName")
        tempFile.delete()

        return@runCatching targetFolder
    }

    /** Downloads an archive and reports its progress. */
    private suspend fun downloadFile(url: String, targetFile: File, githubToken: String?) {
        val response = httpClient.get(url) {
            if (githubToken != null) {
                header("Authorization", "Bearer $githubToken")
                header("Accept", "application/vnd.github+json")
            }
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "Download failed for '${targetFile.name}' with HTTP ${response.status.value}",
            )
        }

        val totalBytes = response.contentLength() ?: 0L
        FileOutputStream(targetFile).use { output ->
            val buffer = ByteArray(8192)
            var downloadedBytes = 0L
            val channel = response.bodyAsChannel()

            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                downloadedBytes += read
                if (totalBytes > 0) printProgress(downloadedBytes, totalBytes)
            }
        }
        println()
    }

    /** Writes the current download progress. */
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

    /** Formats a byte count for progress output. */
    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /** Extracts a ZIP archive. */
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

    /** Extracts a TAR.GZ archive. */
    private fun extractTarGz(archive: File, targetDir: File) {
        archive.inputStream().use { fis ->
            GZIPInputStream(fis).use { gzis ->
                TarArchiveInputStream(gzis).use { tais ->
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

    /** Extracts a TAR.XZ archive. */
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

    /** Resolves release metadata from GitHub. */
    private suspend fun resolveRelease(version: String, githubToken: String? = null): JsonObject {
        val urls = if (version == "latest") {
            listOf(REPOSITORY)
        } else {
            candidateTags(version).map { "$BASE_URL/tags/$it" }
        }

        val failures = mutableListOf<String>()
        for (url in urls) {
            try {
                val token = githubToken ?: System.getenv("GITHUB_TOKEN")
                val response = httpClient.get(url) {
                    if (token != null && !token.isNullOrEmpty()) {
                        header("Authorization", "Bearer $token")
                        header("Accept", "application/vnd.github+json")
                    } else {
                        header("Accept", "application/vnd.github+json")
                    }
                }

                if (!response.status.isSuccess()) {
                    if (response.status.value == 403 || response.status.value == 429) {
                        throw IllegalStateException(
                            "GitHub API rate limit exceeded!\n" +
                                "   Tip: Set GITHUB_TOKEN environment variable for higher limits.",
                        )
                    }
                    failures += "$url -> HTTP ${response.status.value}"
                    continue
                }

                val channel = response.bodyAsChannel()
                val sb = StringBuilder()
                val buf = ByteArray(8192)
                while (!channel.isClosedForRead) {
                    val n = channel.readAvailable(buf)
                    if (n <= 0) break
                    sb.append(String(buf, 0, n))
                }

                val releaseJson = Json.decodeFromString<JsonObject>(sb.toString())
                if (releaseJson["assets"] != null) {
                    return releaseJson
                } else {
                    failures += "$url -> missing assets"
                }
            } catch (e: Exception) {
                failures += "$url -> ${e.message}"
            }
        }

        throw IllegalStateException(
            "Unable to resolve wasmtime release for '$version'. Tried: ${failures.joinToString("; ")}",
        )
    }

    /** Returns supported release tag forms for a version. */
    private fun candidateTags(version: String): List<String> {
        val raw = version.trim()
        val base = raw.removePrefix("release-").removePrefix("v")
        return listOf(
            "release-v$base",
            "v$base",
            raw,
            "release-$raw",
        ).distinct()
    }
}

/** Finds the expected Wasmtime executable inside an extracted release directory. */
internal fun findExtractedWasmtimeExecutable(directory: File, distribution: WasmtimeDistribution): File? = when (distribution) {
    WasmtimeDistribution.MINIMAL -> WasmtimeCompiler.findWasmtimeExecutable(directory)
    WasmtimeDistribution.FULL -> WasmtimeCompiler.findWasmtimeCompilerExecutable(directory)
}
