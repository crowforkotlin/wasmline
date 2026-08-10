package crow.wasmline.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import crow.wasmline.WasmlineComponentRpcContract
import crow.wasmline.plugin.core.component.ComponentBuildRecords
import crow.wasmline.plugin.core.component.ComponentPipeline
import crow.wasmline.plugin.core.component.ComponentizeRequest
import crow.wasmline.plugin.core.component.WasmToolsTool
import crow.wasmline.plugin.core.toolchain.ExternalToolRunner
import crow.wasmline.plugin.core.toolchain.ToolDownloader
import crow.wasmline.plugin.core.toolchain.ToolchainCatalog
import crow.wasmline.plugin.core.util.PlatformDetector
import kotlinx.coroutines.runBlocking
import java.io.File

/** Converts a compiled Core Wasm file into a validated Component Wasm. */
class Componentize : CliktCommand(name = "componentize") {
    private val inputFile by option("-i", "--input")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
        .required()
    private val witPath by option("--wit")
        .file(mustExist = true, canBeFile = true, canBeDir = true)
        .required()
    private val adapterPath by option("--adapter")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
    private val outputDirectory by option("-o", "--output")
        .file(canBeFile = false, canBeDir = true)
        .default(File("build/wasmline/component"))
    private val name by option("-n", "--name").default("component")
    private val world by option("--world")
    private val exportName by option("--export-name").default(WasmlineComponentRpcContract.DEFAULT_EXPORT)
    private val codec by option("--codec").default(WasmlineComponentRpcContract.DEFAULT_CODEC)
    private val rpcProtocolVersion by option("--rpc-version").default(WasmlineComponentRpcContract.VERSION)
    private val wasmToolsPath by option("--wasm-tools")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
    private val version by option("-v", "--version").default(ToolchainCatalog.WASM_TOOLS_VERSION)
    private val platform by option("-a", "--platform").default(PlatformDetector.detectPlatform())
    private val cacheDirectory by option("--cache")
        .file(canBeFile = false, canBeDir = true)
        .default(defaultToolCacheDirectory())

    override fun run() = runBlocking {
        val downloader = ToolDownloader(logger = ::echo)
        try {
            val tools = resolveComponentToolFiles(
                cacheDirectory = cacheDirectory,
                downloader = downloader,
                platform = platform,
                wasmToolsPath = wasmToolsPath,
                wasmToolsVersion = version,
                adapterPath = adapterPath,
                githubToken = System.getenv("GITHUB_TOKEN"),
            )
            val result = ComponentPipeline(
                WasmToolsTool(tools.wasmTools, ExternalToolRunner(logger = ::echo)),
            ).componentize(
                ComponentizeRequest(
                    coreWasm = inputFile,
                    witPath = witPath,
                    wasiPreview1Adapter = tools.adapter,
                    outputDirectory = outputDirectory,
                    productName = name,
                    world = world,
                    exportName = exportName,
                    codec = codec,
                    rpcProtocolVersion = rpcProtocolVersion,
                    wasmToolsVersion = version,
                    adapterVersion = if (adapterPath == null) {
                        ToolchainCatalog.WASI_PREVIEW1_ADAPTER_VERSION
                    } else {
                        null
                    },
                ),
            )
            ComponentBuildRecords.write(result, File(outputDirectory, ComponentBuildRecords.FILE_NAME))
            echo("Component Wasm: " + result.componentWasm.absolutePath)
            echo("Component SHA-256: " + result.componentSha256)
            echo("WIT SHA-256: " + result.witSha256)
        } catch (error: Exception) {
            echo("Error: " + error.message, err = true)
            throw ProgramResult(1)
        } finally {
            downloader.close()
        }
    }
}

/** Validates a Component Wasm with the locked wasm-tools binary. */
class ComponentValidate : CliktCommand(name = "validate") {
    private val component by option("-i", "--input")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
        .required()
    private val wasmToolsPath by option("--wasm-tools")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
    private val version by option("-v", "--version").default(ToolchainCatalog.WASM_TOOLS_VERSION)
    private val platform by option("-a", "--platform").default(PlatformDetector.detectPlatform())
    private val cacheDirectory by option("--cache")
        .file(canBeFile = false, canBeDir = true)
        .default(defaultToolCacheDirectory())

    override fun run() = runBlocking {
        val downloader = ToolDownloader(logger = ::echo)
        try {
            val tool = resolveWasmToolsFile(
                cacheDirectory = cacheDirectory,
                downloader = downloader,
                platform = platform,
                wasmToolsPath = wasmToolsPath,
                wasmToolsVersion = version,
                githubToken = System.getenv("GITHUB_TOKEN"),
            )
            WasmToolsTool(tool, ExternalToolRunner(logger = ::echo)).also { it.verify(version) }.validate(component)
            echo("Valid Component Wasm: " + component.absolutePath)
        } catch (error: Exception) {
            echo("Error: " + error.message, err = true)
            throw ProgramResult(1)
        } finally {
            downloader.close()
        }
    }
}

/** Prints the WIT world extracted from a Component Wasm. */
class ComponentInspect : CliktCommand(name = "inspect") {
    private val component by option("-i", "--input")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
        .required()
    private val wasmToolsPath by option("--wasm-tools")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
    private val version by option("-v", "--version").default(ToolchainCatalog.WASM_TOOLS_VERSION)
    private val platform by option("-a", "--platform").default(PlatformDetector.detectPlatform())
    private val cacheDirectory by option("--cache")
        .file(canBeFile = false, canBeDir = true)
        .default(defaultToolCacheDirectory())

    override fun run() = runBlocking {
        val downloader = ToolDownloader(logger = ::echo)
        try {
            val tool = resolveWasmToolsFile(
                cacheDirectory = cacheDirectory,
                downloader = downloader,
                platform = platform,
                wasmToolsPath = wasmToolsPath,
                wasmToolsVersion = version,
                githubToken = System.getenv("GITHUB_TOKEN"),
            )
            echo(WasmToolsTool(tool, ExternalToolRunner()).also { it.verify(version) }.inspectWit(component))
        } catch (error: Exception) {
            echo("Error: " + error.message, err = true)
            throw ProgramResult(1)
        } finally {
            downloader.close()
        }
    }
}
