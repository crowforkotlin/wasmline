package crow.wasmline.gradle.tasks

import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import crow.wasmline.plugin.core.download.WasmtimeDistribution
import crow.wasmline.plugin.core.download.WasmtimeDownloader
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
        distribution.convention(WasmtimeDistribution.MINIMAL)
        outputs.upToDateWhen { false }
    }

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val platform: Property<String>

    @get:Input
    abstract val distribution: Property<WasmtimeDistribution>

    @get:Internal
    abstract val wasmtimeDirectory: DirectoryProperty

    @get:Internal
    abstract val githubToken: Property<String>

    @get:OutputFile
    @get:Optional
    abstract val installedExecutable: RegularFileProperty

    /** Downloads Wasmtime when the requested platform or version is unavailable. */
    @TaskAction
    fun download() {
        val targetVersion = version.get()
        val targetPlatform = platform.get()
        val targetDistribution = distribution.get()
        val baseDir = wasmtimeDirectory.orNull?.asFile
            ?: File(System.getProperty("user.home"), ".wasmline/wasmtime")

        findExecutable(baseDir, targetPlatform, targetVersion, targetDistribution)?.let { executable ->
            val installed = installExecutable(executable)
            logger.lifecycle("wasmtime already exists at: ${installed.absolutePath}")
            return
        }

        logger.lifecycle(
            "Downloading ${targetDistribution.name.lowercase()} wasmtime $targetVersion " +
                "for $targetPlatform into ${baseDir.absolutePath}",
        )
        runBlocking {
            val downloader = WasmtimeDownloader()
            try {
                downloader.download(
                    githubToken = githubToken.orNull,
                    version = targetVersion,
                    platform = targetPlatform,
                    distribution = targetDistribution,
                    outputDir = baseDir,
                )
            } finally {
                downloader.close()
            }
        }
        val executable = checkNotNull(findExecutable(baseDir, targetPlatform, targetVersion, targetDistribution)) {
            "wasmtime download completed but no executable was found in ${baseDir.absolutePath}"
        }
        installExecutable(executable)
    }

    private fun findExecutable(baseDir: File, platform: String, version: String, distribution: WasmtimeDistribution): File? =
        when (distribution) {
            WasmtimeDistribution.MINIMAL -> WasmtimeCompiler.findWasmtimeInDirectory(baseDir, platform, version)
            WasmtimeDistribution.FULL -> WasmtimeCompiler.findWasmtimeCompilerInDirectory(baseDir, platform, version)
        }

    private fun installExecutable(executable: File): File {
        val destination = installedExecutable.orNull?.asFile ?: return executable
        if (executable.canonicalFile == destination.canonicalFile) return executable
        destination.parentFile?.mkdirs()
        Files.copy(executable.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        if (!System.getProperty("os.name").lowercase().contains("win")) {
            check(destination.setExecutable(true)) {
                "Unable to mark installed Wasmtime executable: " + destination.absolutePath
            }
        }
        return destination
    }
}
