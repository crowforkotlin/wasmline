package crow.wasmline.gradle.tasks

import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import crow.wasmline.plugin.core.download.WasmtimeDownloader
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Downloads the configured Wasmtime toolchain.
 *
 * The task checks the local executable on every invocation. This keeps the
 * toolchain check correct when a directory is changed outside Gradle.
 *
 * 2026/7/31
 * @author crowforkotlin
 */
abstract class DownloadWasmtimeTask : DefaultTask() {

    init {
        group = "wasmline"
        description = "Download wasmtime binary"
        outputs.upToDateWhen { false }
    }

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val platform: Property<String>

    @get:Internal
    abstract val wasmtimeDirectory: DirectoryProperty

    @get:Internal
    abstract val githubToken: Property<String>

    /** Downloads Wasmtime when the requested platform or version is unavailable. */
    @TaskAction
    fun download() {
        val targetVersion = version.get()
        val targetPlatform = platform.get()
        val baseDir = wasmtimeDirectory.orNull?.asFile
            ?: File(System.getProperty("user.home"), ".wasmline/wasmtime")

        WasmtimeCompiler.findWasmtimeInDirectory(baseDir, targetPlatform)?.let { executable ->
            if (hasRequestedVersion(executable, targetVersion)) {
                logger.lifecycle("wasmtime already exists at: ${executable.absolutePath}")
                return
            }
            logger.lifecycle("wasmtime version does not match $targetVersion: ${executable.absolutePath}")
        }

        logger.lifecycle("Downloading wasmtime $targetVersion for $targetPlatform into ${baseDir.absolutePath}")
        runBlocking {
            val downloader = WasmtimeDownloader()
            try {
                downloader.download(
                    githubToken = githubToken.orNull,
                    version = targetVersion,
                    platform = targetPlatform,
                    outputDir = baseDir,
                )
            } finally {
                downloader.close()
            }
        }
        checkNotNull(WasmtimeCompiler.findWasmtimeInDirectory(baseDir, targetPlatform)) {
            "wasmtime download completed but no executable was found in ${baseDir.absolutePath}"
        }
    }

    private fun hasRequestedVersion(executable: File, targetVersion: String): Boolean {
        if (targetVersion == "latest") return true

        val expectedVersion = targetVersion.removePrefix("release-").removePrefix("v")
        return runCatching {
            val process = ProcessBuilder(executable.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            val versionOutput = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor() == 0 && versionOutput.contains("wasmtime $expectedVersion")
        }.getOrDefault(false)
    }
}
