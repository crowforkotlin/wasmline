package crow.wasmline.plugin.core.aot

import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import crow.wasmline.plugin.core.toolchain.FileDigest
import crow.wasmline.plugin.core.toolchain.SecureArchiveExtractor
import crow.wasmline.plugin.core.toolchain.ToolDistribution
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Properties

/**
 * Contains one verified compiler executable resolved for an AOT profile.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
data class ResolvedAotCompiler(
    val profile: AotCompatibilityProfileSpec,
    val asset: AotCompilerAssetSpec,
    val directory: File,
    val executable: File,
)

/**
 * Stores compiler archives by immutable archive digest and build host.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
class AotCompilerCache(val rootDirectory: File) {
    /** Returns the content-addressed directory for one compiler asset. */
    fun directoryFor(asset: AotCompilerAssetSpec): File = File(
        rootDirectory,
        "sha256/${asset.archiveSha256.take(2)}/${asset.archiveSha256}/${asset.buildHost}",
    )

    /** Returns the cross-process lock file for one compiler archive. */
    fun lockFileFor(asset: AotCompilerAssetSpec): File = File(
        rootDirectory,
        ".locks/${asset.archiveSha256}-${asset.buildHost}.lock",
    )

    /** Resolves an asset only when archive and executable identities match its marker. */
    fun resolve(profile: AotCompatibilityProfileSpec, asset: AotCompilerAssetSpec): ResolvedAotCompiler? {
        val directory = directoryFor(asset)
        val marker = File(directory, MARKER_FILE_NAME)
        if (!marker.isFile) return null
        val properties = runCatching {
            Properties().apply { marker.inputStream().use { input -> load(input) } }
        }.getOrNull() ?: return null
        if (properties.getProperty("archiveSha256") != asset.archiveSha256 ||
            properties.getProperty("executableSha256") != asset.executableSha256 ||
            properties.getProperty("buildHost") != asset.buildHost
        ) {
            return null
        }
        val executable = File(directory, asset.executableRelativePath).canonicalFile
        if (!executable.toPath().startsWith(directory.canonicalFile.toPath()) || !executable.isFile) return null
        if (FileDigest.sha256Hex(executable) != asset.executableSha256) return null
        if (!executable.canExecute() && !executable.setExecutable(true, false)) return null
        return ResolvedAotCompiler(profile, asset, directory, executable)
    }

    /** Writes the immutable compiler-asset marker while the caller holds its file lock. */
    fun writeMarker(asset: AotCompilerAssetSpec, directory: File) {
        val marker = File(directory, MARKER_FILE_NAME)
        Properties().apply {
            setProperty("archiveSha256", asset.archiveSha256)
            setProperty("executableSha256", asset.executableSha256)
            setProperty("buildHost", asset.buildHost)
        }.also { properties -> marker.outputStream().use { properties.store(it, "Verified Wasmline AOT compiler") } }
    }

    /**
     * Defines the verified compiler cache marker filename.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    private companion object {
        const val MARKER_FILE_NAME: String = ".wasmline-aot-compiler"
    }
}

/**
 * Downloads, verifies, and atomically publishes catalog compiler assets.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
class AotCompilerResolver(private val cache: AotCompilerCache, private val httpClient: HttpClient = HttpClient(CIO)) : Closeable {
    /** Resolves every selected profile and reports all offline cache misses together. */
    suspend fun resolveAll(
        profiles: Collection<AotCompatibilityProfileSpec>,
        buildHost: String,
        autoDownload: Boolean,
        githubToken: String? = null,
        maxParallelDownloads: Int = DEFAULT_MAX_PARALLEL_DOWNLOADS,
    ): Map<String, ResolvedAotCompiler> {
        require(maxParallelDownloads > 0) { "maxParallelDownloads must be positive." }
        val requests = profiles.associateWith { AotCompatibilityCatalog.requireCompilerAsset(it.id, buildHost) }
        val resolved = requests.mapNotNull { (profile, asset) ->
            cache.resolve(profile, asset)?.let { profile.id to it }
        }.toMap().toMutableMap()
        val missing = requests.filterKeys { it.id !in resolved }
        if (missing.isNotEmpty() && !autoDownload) {
            error(
                "Missing verified AOT compiler assets:\n" + missing.entries.joinToString("\n") { (profile, asset) ->
                    "  - ${profile.id} (${profile.wasmtimeVersion}/${profile.artifactBackend}, ${asset.buildHost})"
                },
            )
        }
        val missingByAsset = missing.entries.groupBy { (_, asset) -> asset.archiveSha256 to asset.buildHost }
        val semaphore = Semaphore(maxParallelDownloads)
        val downloaded = coroutineScope {
            missingByAsset.values.map { entries ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        resolveOrDownload(
                            profiles = entries.map { it.key },
                            asset = entries.first().value,
                            githubToken = githubToken,
                        )
                    }
                }
            }.awaitAll().flatMap { it.entries }.associate { it.toPair() }
        }
        resolved.putAll(downloaded)
        return resolved
    }

    override fun close() {
        httpClient.close()
    }

    private suspend fun resolveOrDownload(
        profiles: List<AotCompatibilityProfileSpec>,
        asset: AotCompilerAssetSpec,
        githubToken: String?,
    ): Map<String, ResolvedAotCompiler> = withContext(Dispatchers.IO) {
        require(profiles.isNotEmpty()) { "At least one AOT profile is required for a compiler asset." }
        val lockFile = cache.lockFileFor(asset).apply { parentFile.mkdirs() }
        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val first = profiles.first()
                cache.resolve(first, asset) ?: install(first, asset, githubToken)
                profiles.associate { profile ->
                    profile.id to (
                        cache.resolve(profile, asset)
                            ?: error("AOT compiler cache verification failed for profile '${profile.id}'.")
                        )
                }
            }
        }
    }

    private suspend fun install(
        profile: AotCompatibilityProfileSpec,
        asset: AotCompilerAssetSpec,
        githubToken: String?,
    ): ResolvedAotCompiler {
        val destination = cache.directoryFor(asset)
        val parent = destination.parentFile.apply { mkdirs() }
        val download = Files.createTempFile(parent.toPath(), ".download-", ".tmp").toFile()
        val staging = Files.createTempDirectory(parent.toPath(), ".install-").toFile()
        try {
            downloadFromMirrors(asset, download, githubToken)
            require(download.length() == asset.archiveSize) {
                "Compiler archive '${asset.archiveName}' size mismatch."
            }
            require(FileDigest.sha256Hex(download) == asset.archiveSha256) {
                "Compiler archive '${asset.archiveName}' SHA-256 mismatch."
            }
            SecureArchiveExtractor.extract(download, asset.archiveFormat.toToolDistribution(), staging)
            val executable = File(staging, asset.executableRelativePath).canonicalFile
            require(executable.toPath().startsWith(staging.canonicalFile.toPath()) && executable.isFile) {
                "Compiler archive '${asset.archiveName}' is missing '${asset.executableRelativePath}'."
            }
            require(FileDigest.sha256Hex(executable) == asset.executableSha256) {
                "Compiler executable '${asset.archiveName}' SHA-256 mismatch."
            }
            if (!executable.canExecute()) {
                require(executable.setExecutable(true, false)) {
                    "Unable to mark AOT compiler executable: ${executable.absolutePath}"
                }
            }
            require(WasmtimeCompiler.detectWasmtimeVersion(executable) == profile.wasmtimeVersion) {
                "AOT compiler '${asset.archiveName}' does not report Wasmtime ${profile.wasmtimeVersion}."
            }
            require(hasCompileCapability(executable)) {
                "AOT compiler '${asset.archiveName}' does not provide the compile subcommand."
            }
            if (destination.exists()) {
                check(destination.deleteRecursively()) {
                    "Unable to replace AOT compiler cache directory: ${destination.absolutePath}"
                }
            }
            runCatching {
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(staging.toPath(), destination.toPath())
            }
            cache.writeMarker(asset, destination)
            return cache.resolve(profile, asset)
                ?: error("Installed AOT compiler failed cache verification: ${asset.archiveName}")
        } finally {
            download.delete()
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private suspend fun downloadFromMirrors(asset: AotCompilerAssetSpec, destination: File, githubToken: String?) {
        var failure: Throwable? = null
        asset.downloadUrls.forEach { url ->
            try {
                val response = httpClient.get(url) {
                    if (!githubToken.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $githubToken")
                }
                check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
                FileOutputStream(destination).use { output ->
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var written = 0L
                    while (!channel.isClosedForRead) {
                        val count = channel.readAvailable(buffer)
                        if (count < 0) break
                        if (count > 0) {
                            written += count
                            require(written <= asset.archiveSize) { "Compiler archive exceeds its locked size." }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = error
            }
        }
        throw IllegalStateException("Unable to download compiler archive '${asset.archiveName}'.", failure)
    }

    private fun hasCompileCapability(executable: File): Boolean = runCatching {
        val process = ProcessBuilder(executable.absolutePath, "compile", "--help")
            .redirectErrorStream(true)
            .start()
        process.inputStream.use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
        process.waitFor() == 0
    }.getOrDefault(false)

    private fun AotCompilerArchiveFormat.toToolDistribution(): ToolDistribution = when (this) {
        AotCompilerArchiveFormat.TAR_GZ -> ToolDistribution.TAR_GZ
        AotCompilerArchiveFormat.ZIP -> ToolDistribution.ZIP
    }

    /**
     * Defines bounded compiler download defaults.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    private companion object {
        const val DEFAULT_MAX_PARALLEL_DOWNLOADS: Int = 2
    }
}
