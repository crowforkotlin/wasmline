package crow.wasmline.gradle.tasks

import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import crow.wasmline.plugin.core.download.WasmtimeDownloader
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class DownloadWasmtimeTask : DefaultTask() {

    init {
        group = "wasmline"
        description = "Download wasmtime binary"
    }

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val platform: Property<String>

    @get:OutputDirectory
    @get:Optional
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val wasmtimeDirectory: DirectoryProperty

    @get:Internal
    abstract val githubToken: Property<String>

    @TaskAction
    fun download() {
        val targetVersion = version.get()
        val targetPlatform = platform.get()
        val baseDir = wasmtimeDirectory.orNull?.asFile
            ?: outputDir.orNull?.asFile
            ?: File(System.getProperty("user.home"), ".wasmline/wasmtime")

        WasmtimeCompiler.findWasmtimeInDirectory(baseDir, targetPlatform)?.let { executable ->
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
        checkNotNull(WasmtimeCompiler.findWasmtimeInDirectory(baseDir, targetPlatform)) {
            "wasmtime download completed but no executable was found in ${baseDir.absolutePath}"
        }
    }
}
