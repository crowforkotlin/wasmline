package crow.wasmline.gradle.tasks

import crow.wasmline.plugin.core.component.WasmToolsTool
import crow.wasmline.plugin.core.component.WitBindgenTool
import crow.wasmline.plugin.core.toolchain.ExternalToolRunner
import crow.wasmline.plugin.core.toolchain.ToolCache
import crow.wasmline.plugin.core.toolchain.ToolDownloader
import crow.wasmline.plugin.core.toolchain.ToolResolver
import crow.wasmline.plugin.core.toolchain.ToolchainCatalog
import crow.wasmline.plugin.core.toolchain.WasmlineTool
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/** Downloads and verifies the pinned Component Model build tools. */
abstract class DownloadComponentToolsTask : DefaultTask() {
    @get:Internal
    abstract val toolCacheDirectory: DirectoryProperty

    @get:Input
    abstract val witBindgenVersion: Property<String>

    @get:Input
    abstract val wasmToolsVersion: Property<String>

    @get:Input
    abstract val platform: Property<String>

    @get:Input
    abstract val force: Property<Boolean>

    @get:Internal
    abstract val githubToken: Property<String>

    @TaskAction
    fun download() = runBlocking {
        val downloader = ToolDownloader(logger = { message -> logger.lifecycle(message) })
        try {
            val resolver = ToolResolver(ToolCache(toolCacheDirectory.get().asFile), downloader)
            val witBindgen = resolver.resolve(
                tool = WasmlineTool.WIT_BINDGEN,
                version = witBindgenVersion.get(),
                platform = platform.get(),
                force = force.get(),
                githubToken = githubToken.orNull,
            )
            val wasmTools = resolver.resolve(
                tool = WasmlineTool.WASM_TOOLS,
                version = wasmToolsVersion.get(),
                platform = platform.get(),
                force = force.get(),
                githubToken = githubToken.orNull,
            )
            val adapter = resolver.resolve(
                tool = WasmlineTool.WASI_PREVIEW1_REACTOR_ADAPTER,
                version = ToolchainCatalog.WASI_PREVIEW1_ADAPTER_VERSION,
                platform = ToolchainCatalog.UNIVERSAL_PLATFORM,
                force = force.get(),
                githubToken = githubToken.orNull,
            )
            WitBindgenTool(
                executable = witBindgen.file,
                runner = ExternalToolRunner(logger = { message -> logger.info(message) }),
            ).verify(witBindgenVersion.get())
            WasmToolsTool(
                executable = wasmTools.file,
                runner = ExternalToolRunner(logger = { message -> logger.info(message) }),
            ).verify(wasmToolsVersion.get())
            logger.lifecycle("wit-bindgen: " + witBindgen.file.absolutePath)
            logger.lifecycle("wasm-tools: " + wasmTools.file.absolutePath)
            logger.lifecycle("WASI Preview 1 adapter: " + adapter.file.absolutePath)
        } finally {
            downloader.close()
        }
    }
}
