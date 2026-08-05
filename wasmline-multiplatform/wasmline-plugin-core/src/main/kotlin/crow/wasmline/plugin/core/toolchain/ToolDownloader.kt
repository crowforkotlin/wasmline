package crow.wasmline.plugin.core.toolchain

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Downloads locked tool assets into a verified local cache.
 *
 * Every network response is checked against the catalog digest before any
 * extracted file can become visible as a resolved tool.
 */
class ToolDownloader(
    private val httpClient: HttpClient = HttpClient(CIO),
    private val logger: (String) -> Unit = {},
) : Closeable {
    /**
     * Resolves a cached asset or downloads it when it is missing.
     *
     * A per-asset file lock prevents concurrent Gradle or CLI processes from
     * replacing the same cache directory.
     */
    suspend fun resolveOrDownload(
        spec: ToolAssetSpec,
        cache: ToolCache,
        githubToken: String? = null,
        force: Boolean = false,
    ): ResolvedToolAsset = withContext(Dispatchers.IO) {
        if (!force) cache.resolve(spec)?.let { return@withContext it }

        val lockFile = cache.lockFileFor(spec)
        lockFile.parentFile.mkdirs()
        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                if (!force) cache.resolve(spec)?.let { return@withContext it }
                install(spec, cache, githubToken)
            }
        }
    }

    override fun close() {
        httpClient.close()
    }

    private suspend fun install(spec: ToolAssetSpec, cache: ToolCache, githubToken: String?): ResolvedToolAsset {
        val destination = cache.directoryFor(spec)
        val parent = destination.parentFile.apply { mkdirs() }
        val download = Files.createTempFile(parent.toPath(), ".download-", ".tmp").toFile()
        val staging = Files.createTempDirectory(parent.toPath(), ".install-").toFile()

        try {
            logger("Downloading " + spec.archiveName)
            downloadFile(spec.downloadUrl, download, githubToken)
            verifyDigest(download, spec)

            when (spec.distribution) {
                ToolDistribution.FILE ->
                    Files.copy(download.toPath(), File(staging, spec.entryFileName).toPath(), StandardCopyOption.REPLACE_EXISTING)

                ToolDistribution.TAR_GZ,
                ToolDistribution.ZIP,
                -> SecureArchiveExtractor.extract(download, spec.distribution, staging)
            }

            val entry = findEntry(staging, spec.entryFileName)
                ?: error("Downloaded asset does not contain " + spec.entryFileName + ": " + spec.archiveName)
            if (spec.executable && !entry.setExecutable(true, false)) {
                require(entry.canExecute()) { "Unable to mark tool executable: " + entry.absolutePath }
            }
            val relativeEntry = entry.relativeTo(staging).path

            if (destination.exists()) {
                check(destination.deleteRecursively()) { "Unable to replace tool cache directory: " + destination.absolutePath }
            }
            moveDirectory(staging, destination)
            cache.writeMarker(spec, relativeEntry)
            logger("Installed " + spec.tool.name.lowercase() + " " + spec.version + " for " + spec.platform)
            return cache.resolve(spec) ?: error("Installed tool failed cache verification: " + spec.archiveName)
        } finally {
            download.delete()
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private suspend fun downloadFile(url: String, destination: File, githubToken: String?) {
        val response = httpClient.get(url) {
            if (!githubToken.isNullOrBlank()) {
                header(HttpHeaders.Authorization, "Bearer $githubToken")
                header(HttpHeaders.Accept, "application/octet-stream")
            }
        }
        check(response.status.isSuccess()) {
            "Download failed with HTTP " + response.status.value + ": " + url
        }

        FileOutputStream(destination).use { output ->
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (!channel.isClosedForRead) {
                val count = channel.readAvailable(buffer)
                if (count < 0) break
                if (count > 0) output.write(buffer, 0, count)
            }
        }
    }

    internal fun verifyDigest(download: File, spec: ToolAssetSpec) {
        val actualDigest = FileDigest.sha256Hex(download)
        require(actualDigest.equals(spec.sha256, ignoreCase = true)) {
            "SHA-256 mismatch for " + spec.archiveName + ": expected " + spec.sha256 + ", actual " + actualDigest + "."
        }
    }

    private fun findEntry(directory: File, expectedName: String): File? =
        directory.walkTopDown().firstOrNull { it.isFile && it.name.equals(expectedName, ignoreCase = true) }

    private fun moveDirectory(source: File, destination: File) {
        runCatching {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source.toPath(), destination.toPath())
        }
    }
}
