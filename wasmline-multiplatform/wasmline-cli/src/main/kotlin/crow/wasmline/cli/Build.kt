package crow.wasmline.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.unique
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.long
import crow.wasmline.WasmlineComponentRpcContract
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import crow.wasmline.plugin.core.component.ComponentBuildRecords
import crow.wasmline.plugin.core.component.ComponentPipeline
import crow.wasmline.plugin.core.component.ComponentizeRequest
import crow.wasmline.plugin.core.component.ExistingComponentRequest
import crow.wasmline.plugin.core.component.WasmToolsTool
import crow.wasmline.plugin.core.diagnostics.WasmlineArtifactDiagnostics
import crow.wasmline.plugin.core.manifest.ManifestSigner
import crow.wasmline.plugin.core.packaging.PluginPackager
import crow.wasmline.plugin.core.toolchain.ExternalToolRunner
import crow.wasmline.plugin.core.toolchain.ToolDownloader
import crow.wasmline.plugin.core.toolchain.ToolchainCatalog
import crow.wasmline.plugin.core.util.PlatformDetector
import kotlinx.coroutines.runBlocking
import java.io.File

/** Builds, signs and packages either a Core Wasm or Component Model plugin. */
class Build : CliktCommand(name = "build") {
    private val inputFile by option("-i", "--input")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
        .required()
    private val name by option("-n", "--name")
    private val wasmtimeDir by option("-wt", "--wasmtime")
        .file(mustExist = true, canBeDir = true, canBeFile = false)
    private val targets by option("-a", "--arch").multiple().unique()
    private val pluginId by option("--plugin-id")
    private val version by option("-v", "--version").default("1.0.0")
    private val versionCode by option("--version-code").long().default(1L)
    private val minSdkVersion by option("--min-sdk").default(BuildConfig.VERSION)
    private val displayName by option("--display-name")
    private val author by option("--author")
    private val description by option("--description")
    private val iconUrl by option("--icon-url")
    private val homeUrl by option("--home-url")
    private val executionModel by option("--execution-model").default(WasmlineExecutionModel.CORE_WASM.name)
    private val invocationProtocol by option("--invocation-protocol")
    private val exportName by option("--export-name")
    private val codec by option("--codec").default(WasmlineComponentRpcContract.DEFAULT_CODEC)
    private val rpcProtocolVersion by option("--rpc-version").default(WasmlineComponentRpcContract.VERSION)
    private val rawComponent by option("--raw-component")
        .flag(default = false)
    private val contractMetadata by option("--contract-metadata").multiple().unique()
    private val key by option("-k", "--key").required().help("Ed25519 private key: file path or hex string")
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
        try {
            val componentBuild = executionModel.equals(WasmlineExecutionModel.COMPONENT_MODEL.name, ignoreCase = true)
            val effectiveProtocol = invocationProtocol ?: if (componentBuild) {
                WasmlineInvocationProtocol.COMPONENT_EXPORT.name
            } else {
                WasmlineInvocationProtocol.WASMLINE_CORE_V1.name
            }
            val effectiveExport = exportName ?: if (componentBuild) WasmlineComponentRpcContract.DEFAULT_EXPORT else null
            val invocation = parseInvocationOptions(
                executionModelName = executionModel,
                invocationProtocolName = effectiveProtocol,
                exportName = effectiveExport,
                contractMetadataEntries = contractMetadata,
            )
            val productName = name ?: inputFile.nameWithoutExtension
            val outputDir = File("build/wasmline/output", productName + "-" + version).apply { mkdirs() }
            val artifacts = when (invocation.executionModel) {
                WasmlineExecutionModel.CORE_WASM -> compileCore(outputDir, productName)
                WasmlineExecutionModel.COMPONENT_MODEL -> compileComponent(
                    outputDir = outputDir,
                    productName = productName,
                    componentExportName = requireNotNull(invocation.exportName),
                )
            }
            check(artifacts.isNotEmpty()) { "No artifacts were produced." }

            WasmtimeCompiler().writeCompileResult(
                inputFile = inputFile,
                debugDir = File(outputDir, "debug"),
                artifacts = artifacts,
                wasmtimeVersion = BuildConfig.WASMTIME_VERSION,
            )
            val manifestFile = ManifestSigner().createSignedManifest(
                artifacts = artifacts,
                pluginId = pluginId ?: productName,
                version = version,
                versionCode = versionCode,
                minSdkVersion = minSdkVersion,
                signingKey = key,
                outputDir = outputDir,
                displayName = displayName,
                author = author,
                description = description,
                iconUrl = iconUrl,
                homePageUrl = homeUrl,
                executionModel = invocation.executionModel,
                invocationProtocol = invocation.invocationProtocol,
                exportName = invocation.exportName,
                contractMetadata = invocation.contractMetadata,
                logger = ::echo,
            )
            val zipFile = PluginPackager.createZip(
                manifestFile = manifestFile,
                artifacts = artifacts,
                artifactDirectory = outputDir,
                destination = File("build/wasmline/dist", productName + "-" + version + ".zip"),
                folderPrefix = productName + "-" + version,
            )
            artifacts.forEach { artifact ->
                echo("Wasmline artifact: " + WasmlineArtifactDiagnostics.format(artifact))
            }
            echo("Package written to: " + zipFile.absolutePath + " (" + zipFile.length() + " bytes)")
        } catch (error: Exception) {
            echo("Error: " + error.message, err = true)
            throw ProgramResult(1)
        }
    }

    private fun compileCore(outputDir: File, productName: String): List<WasmlineArtifact> {
        val wasmtimeDirectory = wasmtimeDir ?: error("--wasmtime is required for CORE_WASM builds.")
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
                ?: error("--wasmtime is required for COMPONENT_MODEL AOT builds.")
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
