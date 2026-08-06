package crow.wasmline.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.unique
import com.github.ajalt.clikt.parameters.types.file
import crow.wasmline.WasmlineComponentRpcContract
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import crow.wasmline.plugin.core.component.ComponentBuildRecords
import crow.wasmline.plugin.core.component.ComponentPipeline
import crow.wasmline.plugin.core.component.ComponentizeRequest
import crow.wasmline.plugin.core.component.ExistingComponentRequest
import crow.wasmline.plugin.core.component.WasmToolsTool
import crow.wasmline.plugin.core.diagnostics.WasmlineArtifactDiagnostics
import crow.wasmline.plugin.core.toolchain.ExternalToolRunner
import crow.wasmline.plugin.core.toolchain.ToolDownloader
import crow.wasmline.plugin.core.toolchain.ToolchainCatalog
import crow.wasmline.plugin.core.util.PlatformDetector
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Compiles a Core Wasm module or a Component Model plugin into native AOT artifacts.
 */
class Compile : CliktCommand(name = "compile") {
    private val inputFile by option("-i", "--input")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
        .required()
    private val name by option("-n", "--name")
    private val version by option("-v", "--version").default("1.0.0")
    private val outputRoot by option("-o", "--output")
        .file(canBeFile = false, canBeDir = true)
        .default(File("build/wasmline/output"))
    private val wasmtimeDir by option("-wt", "--wasmtime")
        .file(mustExist = true, canBeDir = true, canBeFile = false)
    private val targets by option("-a", "--arch").multiple().unique()
    private val executionModel by option("--execution-model").default(WasmlineExecutionModel.CORE_WASM.name)
    private val invocationProtocol by option("--invocation-protocol")
    private val exportName by option("--export-name")
    private val codec by option("--codec").default(WasmlineComponentRpcContract.DEFAULT_CODEC)
    private val rpcProtocolVersion by option("--rpc-version").default(WasmlineComponentRpcContract.VERSION)
    private val rawComponent by option("--raw-component")
        .flag(default = false)
    private val witPath by option("--wit")
        .file(mustExist = true, canBeFile = true, canBeDir = true)
    private val world by option("--world")
    private val adapterPath by option("--adapter")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
    private val wasmToolsPath by option("--wasm-tools")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
    private val toolCache by option("--tool-cache")
        .file(canBeFile = false, canBeDir = true)
        .default(defaultToolCacheDirectory())
    private val wasmToolsVersion by option("--wasm-tools-version")
        .default(ToolchainCatalog.WASM_TOOLS_VERSION)

    override fun run() = runBlocking {
        val productName = name ?: inputFile.nameWithoutExtension
        val outputDir = File(outputRoot, productName + "-" + version).apply { mkdirs() }
        try {
            val componentBuild = executionModel.equals(WasmlineExecutionModel.COMPONENT_MODEL.name, ignoreCase = true)
            val effectiveProtocol = invocationProtocol ?: if (componentBuild) {
                "COMPONENT_EXPORT"
            } else {
                "WASMLINE_CORE"
            }
            val effectiveExport = exportName ?: if (componentBuild) WasmlineComponentRpcContract.DEFAULT_EXPORT else null
            val invocation = parseInvocationOptions(
                executionModelName = executionModel,
                invocationProtocolName = effectiveProtocol,
                exportName = effectiveExport,
                contractMetadataEntries = emptyList(),
            )
            val artifacts = when (invocation.executionModel) {
                WasmlineExecutionModel.CORE_WASM -> compileCore(outputDir, productName)
                WasmlineExecutionModel.COMPONENT_MODEL -> compileComponent(
                    outputDir = outputDir,
                    productName = productName,
                    componentExportName = requireNotNull(invocation.exportName),
                )
            }.map { artifact ->
                artifact.copy(
                    executionModel = invocation.executionModel,
                    invocationProtocol = invocation.invocationProtocol,
                    exportName = invocation.exportName,
                )
            }
            check(artifacts.isNotEmpty()) { "No artifacts were produced." }
            val debugDir = File(outputDir, "debug")
            WasmtimeCompiler().writeCompileResult(
                inputFile = inputFile,
                debugDir = debugDir,
                artifacts = artifacts,
                wasmtimeVersion = BuildConfig.WASMTIME_VERSION,
            )
            artifacts.forEach { artifact ->
                echo("Wasmline artifact: " + WasmlineArtifactDiagnostics.format(artifact))
            }
            echo(
                "Compile result written to: " +
                    File(debugDir, WasmtimeCompiler.COMPILE_RESULT_FILE).absolutePath,
            )
        } catch (error: Exception) {
            echo("Error: " + error.message, err = true)
            throw ProgramResult(1)
        }
    }

    private fun compileCore(outputDir: File, productName: String): List<WasmlineArtifact> {
        val wasmtimeDirectory = wasmtimeDir ?: error("--wasmtime is required for CORE_WASM compilation.")
        val executable = WasmtimeCompiler.findWasmtimeInDirectory(wasmtimeDirectory)
            ?: error("Could not find wasmtime in " + wasmtimeDirectory.absolutePath)
        val artifacts = WasmtimeCompiler().compileAll(
            wasmtimeExec = executable,
            inputWasm = inputFile,
            outputDir = outputDir,
            productName = productName,
            targets = targets,
            wasmtimeVersion = BuildConfig.WASMTIME_VERSION,
            logger = ::echo,
        )
        check(artifacts.any { it.type != WasmlineArtifactType.WASM }) {
            "No .cwasm or .pwasm artifacts compiled successfully."
        }
        return artifacts
    }

    private suspend fun compileComponent(outputDir: File, productName: String, componentExportName: String): List<WasmlineArtifact> {
        val downloader = ToolDownloader(logger = ::echo)
        try {
            val platform = PlatformDetector.detectPlatform()
            val existingComponent = rawComponent || inputFile.name.endsWith(".component.wasm", ignoreCase = true)
            val result = if (existingComponent) {
                val wasmTools = resolveWasmToolsFile(
                    cacheDirectory = toolCache,
                    downloader = downloader,
                    platform = platform,
                    wasmToolsPath = wasmToolsPath,
                    wasmToolsVersion = wasmToolsVersion,
                    githubToken = System.getenv("GITHUB_TOKEN"),
                )
                ComponentPipeline(
                    WasmToolsTool(wasmTools, ExternalToolRunner(logger = ::echo)),
                ).describeExisting(
                    ExistingComponentRequest(
                        componentWasm = inputFile,
                        outputDirectory = outputDir,
                        productName = productName,
                        witPath = witPath,
                        world = world,
                        exportName = componentExportName,
                        codec = codec,
                        rpcProtocolVersion = rpcProtocolVersion,
                        wasmToolsVersion = wasmToolsVersion,
                    ),
                )
            } else {
                val wit = witPath ?: error("--wit is required when the Component input is Core Wasm.")
                val tools = resolveComponentToolFiles(
                    cacheDirectory = toolCache,
                    downloader = downloader,
                    platform = platform,
                    wasmToolsPath = wasmToolsPath,
                    wasmToolsVersion = wasmToolsVersion,
                    adapterPath = adapterPath,
                    githubToken = System.getenv("GITHUB_TOKEN"),
                )
                ComponentPipeline(
                    WasmToolsTool(tools.wasmTools, ExternalToolRunner(logger = ::echo)),
                ).componentize(
                    ComponentizeRequest(
                        coreWasm = inputFile,
                        witPath = wit,
                        wasiPreview1Adapter = tools.adapter,
                        outputDirectory = outputDir,
                        productName = productName,
                        world = world,
                        exportName = componentExportName,
                        codec = codec,
                        rpcProtocolVersion = rpcProtocolVersion,
                        wasmToolsVersion = wasmToolsVersion,
                        adapterVersion = if (adapterPath == null) {
                            ToolchainCatalog.WASI_PREVIEW1_ADAPTER_VERSION
                        } else {
                            null
                        },
                    ),
                )
            }
            val rawRecord = ComponentBuildRecords.write(result, File(outputDir, ComponentBuildRecords.FILE_NAME))
            val wasmtimeDirectory = wasmtimeDir
                ?: error("--wasmtime is required for COMPONENT_MODEL AOT compilation.")
            return ComponentAotCliAdapter(logger = ::echo).compile(
                ComponentAotCliRequest(
                    rawComponent = rawRecord,
                    componentDirectory = outputDir,
                    outputDirectory = outputDir,
                    productName = productName,
                    wasmtimeDirectory = wasmtimeDirectory,
                    targets = targets,
                    wasmtimeVersion = BuildConfig.WASMTIME_VERSION,
                ),
            ).artifacts
        } finally {
            downloader.close()
        }
    }
}
