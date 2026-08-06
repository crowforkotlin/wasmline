package crow.wasmline.gradle.tasks

import crow.wasmline.plugin.core.component.ComponentBuildRecords
import crow.wasmline.plugin.core.component.ComponentPipeline
import crow.wasmline.plugin.core.component.ComponentizeRequest
import crow.wasmline.plugin.core.component.ExistingComponentRequest
import crow.wasmline.plugin.core.component.WasmToolsTool
import crow.wasmline.plugin.core.toolchain.ExternalToolRunner
import crow.wasmline.plugin.core.toolchain.ToolCache
import crow.wasmline.plugin.core.toolchain.ToolDownloader
import crow.wasmline.plugin.core.toolchain.ToolResolver
import crow.wasmline.plugin.core.toolchain.ToolchainCatalog
import crow.wasmline.plugin.core.toolchain.WasmlineTool
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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
import java.io.File

/** Converts the Kotlin/Wasm WASI Core Wasm output into a raw Component Wasm. */
abstract class WasmlineComponentizeTask : DefaultTask() {
    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val wasmCompileOutputDirectory: DirectoryProperty

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val witDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val productName: Property<String>

    @get:Input
    abstract val world: Property<String>

    @get:Input
    abstract val exportName: Property<String>

    @get:Input
    abstract val codec: Property<String>

    @get:Input
    abstract val rpcProtocolVersion: Property<String>

    @get:Input
    abstract val witBindgenVersion: Property<String>

    @get:Input
    abstract val wasmToolsVersion: Property<String>

    @get:Input
    abstract val platform: Property<String>

    @get:Input
    abstract val autoDownload: Property<Boolean>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val wasmToolsExecutable: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val wasiPreview1Adapter: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val componentInput: RegularFileProperty

    @get:Internal
    abstract val toolCacheDirectory: DirectoryProperty

    @get:Internal
    abstract val githubToken: Property<String>

    @TaskAction
    fun componentize() = runBlocking {
        val output = outputDirectory.get().asFile.apply { mkdirs() }
        val downloader = ToolDownloader(logger = { message -> logger.info(message) })
        try {
            val resolver = ToolResolver(ToolCache(toolCacheDirectory.get().asFile), downloader)
            val wasmTools = wasmToolsExecutable.orNull?.asFile ?: resolver.resolve(
                tool = WasmlineTool.WASM_TOOLS,
                version = wasmToolsVersion.get(),
                platform = platform.get(),
                autoDownload = autoDownload.get(),
                githubToken = githubToken.orNull,
            ).file
            val pipeline = ComponentPipeline(
                WasmToolsTool(wasmTools, ExternalToolRunner(logger = { message -> logger.info(message) })),
            )
            val result = componentInput.orNull?.asFile?.let { component ->
                pipeline.describeExisting(
                    ExistingComponentRequest(
                        componentWasm = component,
                        outputDirectory = output,
                        productName = productName.get(),
                        witPath = witDirectory.orNull?.asFile?.takeIf(File::exists),
                        world = world.orNull,
                        exportName = exportName.get(),
                        codec = codec.get(),
                        rpcProtocolVersion = rpcProtocolVersion.get(),
                        wasmToolsVersion = wasmToolsVersion.get(),
                    ),
                )
            } ?: run {
                val coreWasm = findCoreWasm(wasmCompileOutputDirectory.get().asFile)
                val adapterWasExplicit = wasiPreview1Adapter.isPresent
                val adapter = wasiPreview1Adapter.orNull?.asFile ?: resolver.resolve(
                    tool = WasmlineTool.WASI_PREVIEW1_REACTOR_ADAPTER,
                    version = ToolchainCatalog.WASI_PREVIEW1_ADAPTER_VERSION,
                    platform = ToolchainCatalog.UNIVERSAL_PLATFORM,
                    autoDownload = autoDownload.get(),
                    githubToken = githubToken.orNull,
                ).file
                pipeline.componentize(
                    ComponentizeRequest(
                        coreWasm = coreWasm,
                        witPath = witDirectory.get().asFile,
                        wasiPreview1Adapter = adapter,
                        outputDirectory = output,
                        productName = productName.get(),
                        world = world.orNull,
                        exportName = exportName.get(),
                        codec = codec.get(),
                        rpcProtocolVersion = rpcProtocolVersion.get(),
                        wasmToolsVersion = wasmToolsVersion.get(),
                        witBindgenVersion = witBindgenVersion.get(),
                        adapterVersion = if (adapterWasExplicit) {
                            null
                        } else {
                            ToolchainCatalog.WASI_PREVIEW1_ADAPTER_VERSION
                        },
                    ),
                )
            }
            ComponentBuildRecords.write(result, File(output, ComponentBuildRecords.FILE_NAME))
            logger.lifecycle("Component Wasm: " + result.componentWasm.absolutePath)
        } finally {
            downloader.close()
        }
    }

    private fun findCoreWasm(directory: File): File {
        val root = directory.absoluteFile
        val candidates = if (root.isDirectory) {
            root.walkTopDown()
                .filter { file ->
                    file.isFile &&
                        file.extension.equals("wasm", ignoreCase = true) &&
                        !file.name.endsWith("-component.wasm", ignoreCase = true)
                }
                .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
        } else {
            emptyList()
        }
        val preferred = candidates.firstOrNull { it.nameWithoutExtension == productName.get() }
        return preferred ?: candidates.singleOrNull() ?: throw GradleException(
            "Expected exactly one Core Wasm input below " + directory.absolutePath +
                ", found " + candidates.size + ".",
        )
    }
}
