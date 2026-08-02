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

        WasmtimeCompiler.findWasmtimeInDirectory(baseDir, targetPlatform, targetVersion)?.let { executable ->
            logger.lifecycle("wasmtime already exists at: ${executable.absolutePath}")
            return
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
        checkNotNull(WasmtimeCompiler.findWasmtimeInDirectory(baseDir, targetPlatform, targetVersion)) {
            "wasmtime download completed but no executable was found in ${baseDir.absolutePath}"
        }
    }
}
