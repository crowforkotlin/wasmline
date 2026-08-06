package crow.wasmline.gradle.tasks

import crow.wasmline.plugin.core.component.KotlinBindingsRequest
import crow.wasmline.plugin.core.component.WitBindgenTool
import crow.wasmline.plugin.core.toolchain.ExternalToolRunner
import crow.wasmline.plugin.core.toolchain.ToolCache
import crow.wasmline.plugin.core.toolchain.ToolDownloader
import crow.wasmline.plugin.core.toolchain.ToolResolver
import crow.wasmline.plugin.core.toolchain.WasmlineTool
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Generates Kotlin guest bindings for the configured WIT world. */
abstract class WasmlineGenerateWitBindingsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val witDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val world: Property<String>

    @get:Input
    abstract val kotlinImports: Property<String>

    @get:Input
    abstract val witBindgenVersion: Property<String>

    @get:Input
    abstract val platform: Property<String>

    @get:Input
    abstract val autoDownload: Property<Boolean>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val witBindgenExecutable: RegularFileProperty

    @get:Internal
    abstract val toolCacheDirectory: DirectoryProperty

    @get:Internal
    abstract val githubToken: Property<String>

    @TaskAction
    fun generate() = runBlocking {
        val downloader = ToolDownloader(logger = { message -> logger.info(message) })
        try {
            val executable = witBindgenExecutable.orNull?.asFile ?: ToolResolver(
                ToolCache(toolCacheDirectory.get().asFile),
                downloader,
            ).resolve(
                tool = WasmlineTool.WIT_BINDGEN,
                version = witBindgenVersion.get(),
                platform = platform.get(),
                autoDownload = autoDownload.get(),
                githubToken = githubToken.orNull,
            ).file
            WitBindgenTool(
                executable = executable,
                runner = ExternalToolRunner(logger = { message -> logger.info(message) }),
            ).generateKotlin(
                KotlinBindingsRequest(
                    witDirectory = witDirectory.get().asFile,
                    outputDirectory = outputDirectory.get().asFile,
                    world = world.orNull,
                    kotlinImports = kotlinImports.orNull,
                    witBindgenVersion = witBindgenVersion.get(),
                ),
            )
        } finally {
            downloader.close()
        }
    }
}
