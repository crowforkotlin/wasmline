package crow.wasmline.plugin.core.toolchain

import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
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

@InternalWasmlineToolingApi
class ToolDownloader(private val logger: (String) -> Unit = {}) : Closeable {
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
        // Connections are opened and released per request, so there is nothing to close.
    }

    private suspend fun install(spec: ToolAssetSpec, cache: ToolCache, githubToken: String?): ResolvedToolAsset {
        val destination = cache.directoryFor(spec)
        val parent = destination.parentFile.apply { mkdirs() }
        val download = Files.createTempFile(parent.toPath(), ".download-", ".tmp").toFile()
        val staging = Files.createTempDirectory(parent.toPath(), ".install-").toFile()

        try {
            logger("Downloading " + spec.archiveName)
            downloadFile(spec.downloadUrl, download, githubToken)
            verifySize(download, spec)
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

    /**
     * Downloads one asset through the JDK HTTP stack.
     *
     * GitHub answers release downloads with a redirect whose `Location` header can
     * exceed the header-line limit of HTTP/1.1 protocol parsers, so redirects are
     * followed explicitly instead of relying on a stack with a fixed header size.
     */
    private suspend fun downloadFile(url: String, destination: File, githubToken: String?) = withContext(Dispatchers.IO) {
        var current = URI(url)
        var redirects = 0
        while (true) {
            require(current.scheme == "https") { "Tool URL must use HTTPS: $current" }
            val connection = current.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.requestMethod = "GET"
                if (!githubToken.isNullOrBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer $githubToken")
                    connection.setRequestProperty("Accept", "application/octet-stream")
                }
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                    require(!location.isNullOrBlank()) { "Tool download redirect omitted Location." }
                    require(++redirects <= MAX_REDIRECTS) { "Tool download redirect limit exceeded." }
                    current = current.resolve(location)
                    continue
                }
                check(status in 200..299) { "Download failed with HTTP " + status + ": " + url }
                connection.inputStream.use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                return@withContext
            } finally {
                connection.disconnect()
            }
        }
    }

    internal fun verifyDigest(download: File, spec: ToolAssetSpec) {
        val actualDigest = FileDigest.sha256Hex(download)
        require(actualDigest.equals(spec.sha256, ignoreCase = true)) {
            "SHA-256 mismatch for " + spec.archiveName + ": expected " + spec.sha256 + ", actual " + actualDigest +
                ". Release asset ID: " + spec.assetId + "."
        }
    }

    internal fun verifySize(download: File, spec: ToolAssetSpec) {
        val actualSize = download.length()
        require(actualSize == spec.size) {
            "Size mismatch for " + spec.archiveName + ": expected " + spec.size + " bytes, actual " + actualSize +
                " bytes. Release asset ID: " + spec.assetId + "."
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

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS: Int = 30_000
        const val READ_TIMEOUT_MILLIS: Int = 120_000
        const val MAX_REDIRECTS: Int = 5
    }
}
