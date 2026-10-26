package crow.wasmline.cli

import crow.wasmline.WasmlineComponentServiceContract
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.loader.model.WasmlineRuntimeContract
import crow.wasmline.plugin.core.aot.AotCompatibilityCatalog
import crow.wasmline.plugin.core.aot.AotCompatibilityResolution
import crow.wasmline.plugin.core.aot.AotCompatibilitySelection
import crow.wasmline.plugin.core.aot.WasmlineAotBuildRecord
import crow.wasmline.plugin.core.aot.WasmlineAotBuildRequest
import crow.wasmline.plugin.core.aot.WasmlineAotBuildService
import crow.wasmline.plugin.core.aot.WasmlineAotTargetSpec
import crow.wasmline.plugin.core.aot.WasmlineArtifactTargetFactory
import crow.wasmline.plugin.core.aot.WasmlineVersionRange
import crow.wasmline.plugin.core.component.ComponentBuildRecords
import crow.wasmline.plugin.core.component.ComponentPipeline
import crow.wasmline.plugin.core.component.ComponentizeRequest
import crow.wasmline.plugin.core.component.ExistingComponentRequest
import crow.wasmline.plugin.core.component.WasmToolsTool
import crow.wasmline.plugin.core.toolchain.ExternalToolRunner
import crow.wasmline.plugin.core.toolchain.ToolDownloader
import crow.wasmline.plugin.core.toolchain.ToolchainCatalog
import crow.wasmline.plugin.core.util.PlatformDetector
import java.io.File

/**
 * Defines Component preparation options shared by CLI build and compile commands.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
internal data class CliComponentPreparationRequest(
    val inputFile: File,
    val outputDirectory: File,
    val productName: String,
    val invocation: InvocationOptions,
    val inputIsRawComponent: Boolean,
    val witPath: File?,
    val world: String?,
    val adapterPath: File?,
    val wasmToolsPath: File?,
    val toolCacheDirectory: File,
    val wasmToolsVersion: String,
    val codec: String,
    val serviceProtocolVersion: String,
)

/**
 * Contains a finished raw input and its package-wide runtime contract.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
internal data class CliPreparedAotInput(val file: File, val runtimeContract: WasmlineRuntimeContract)

/**
 * Adapts CLI inputs to the shared Component and AOT build services.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
internal class CliAotBuildSupport(private val logger: (String) -> Unit = {}) {
    /** Prepares one raw Component without publishing it as a runtime artifact. */
    suspend fun prepareComponent(request: CliComponentPreparationRequest): CliPreparedAotInput {
        require(request.invocation.executionModel == WasmlineExecutionModel.COMPONENT_MODEL) {
            "Component preparation requires executionModel=COMPONENT_MODEL."
        }
        val downloader = ToolDownloader(logger = logger)
        try {
            val platform = PlatformDetector.detectPlatform()
            val existingComponent = request.inputIsRawComponent ||
                request.inputFile.name.endsWith(".component.wasm", ignoreCase = true)
            val result = if (existingComponent) {
                val wasmTools = resolveWasmToolsFile(
                    cacheDirectory = request.toolCacheDirectory,
                    downloader = downloader,
                    platform = platform,
                    wasmToolsPath = request.wasmToolsPath,
                    wasmToolsVersion = request.wasmToolsVersion,
                    githubToken = githubToken(),
                )
                ComponentPipeline(WasmToolsTool(wasmTools, ExternalToolRunner(logger = logger))).describeExisting(
                    ExistingComponentRequest(
                        componentWasm = request.inputFile,
                        outputDirectory = request.outputDirectory,
                        productName = request.productName,
                        witPath = request.witPath,
                        world = request.world,
                        invocationProtocol = request.invocation.invocationProtocol,
                        exportName = request.invocation.exportName,
                        codec = componentServiceValue(request.invocation.invocationProtocol, request.codec),
                        serviceProtocolVersion = componentServiceValue(
                            request.invocation.invocationProtocol,
                            request.serviceProtocolVersion,
                        ),
                        wasmToolsVersion = request.wasmToolsVersion,
                    ),
                )
            } else {
                val wit = request.witPath ?: error("--wit is required when the Component input is Core Wasm.")
                val tools = resolveComponentToolFiles(
                    cacheDirectory = request.toolCacheDirectory,
                    downloader = downloader,
                    platform = platform,
                    wasmToolsPath = request.wasmToolsPath,
                    wasmToolsVersion = request.wasmToolsVersion,
                    adapterPath = request.adapterPath,
                    githubToken = githubToken(),
                )
                ComponentPipeline(WasmToolsTool(tools.wasmTools, ExternalToolRunner(logger = logger))).componentize(
                    ComponentizeRequest(
                        coreWasm = request.inputFile,
                        witPath = wit,
                        wasiPreview1Adapter = tools.adapter,
                        outputDirectory = request.outputDirectory,
                        productName = request.productName,
                        world = request.world,
                        invocationProtocol = request.invocation.invocationProtocol,
                        exportName = request.invocation.exportName,
                        codec = componentServiceValue(request.invocation.invocationProtocol, request.codec),
                        serviceProtocolVersion = componentServiceValue(
                            request.invocation.invocationProtocol,
                            request.serviceProtocolVersion,
                        ),
                        wasmToolsVersion = request.wasmToolsVersion,
                        adapterVersion = if (request.adapterPath == null) {
                            ToolchainCatalog.WASI_PREVIEW1_ADAPTER_VERSION
                        } else {
                            null
                        },
                    ),
                )
            }
            val record = ComponentBuildRecords.write(result, File(request.outputDirectory, ComponentBuildRecords.FILE_NAME))
            val generatedContract = record.runtimeContract()
            val configuredMetadata = request.invocation.contractMetadata
            val conflicts = configuredMetadata.keys.filter { key ->
                key in generatedContract.contractMetadata && configuredMetadata[key] != generatedContract.contractMetadata[key]
            }
            require(conflicts.isEmpty()) {
                "Component contract metadata conflicts with generated values: ${conflicts.sorted().joinToString()}."
            }
            return CliPreparedAotInput(
                file = record.validateComponentFile(request.outputDirectory),
                runtimeContract = generatedContract.copy(
                    contractMetadata = generatedContract.contractMetadata + configuredMetadata,
                ),
            )
        } finally {
            downloader.close()
        }
    }

    /** Builds one complete catalog profile and target matrix from an already resolved selection. */
    suspend fun buildMatrix(
        input: CliPreparedAotInput,
        packageDirectory: File,
        workingDirectory: File,
        targets: Collection<String>,
        resolution: AotCompatibilityResolution,
        publishRawWasm: Boolean,
        compilerCacheDirectory: File,
        autoDownload: Boolean,
        maxParallelCompilations: Int,
    ): WasmlineAotBuildRecord {
        return WasmlineAotBuildService().build(
            WasmlineAotBuildRequest(
                inputFile = input.file,
                packageDirectory = packageDirectory,
                workingDirectory = workingDirectory,
                runtimeContract = input.runtimeContract,
                targets = targets,
                resolvedProfileIds = resolution.profiles.map { it.id },
                aotCompatibilitySelector = resolution.selection.diagnosticName(),
                selectedAotGenerations = resolution.selectedGenerations,
                publishRawWasm = publishRawWasm,
                compilerCacheDirectory = compilerCacheDirectory,
                buildHost = PlatformDetector.detectPlatform(),
                autoDownload = autoDownload,
                githubToken = githubToken(),
                maxParallelCompilations = maxParallelCompilations,
                logger = logger,
            ),
        )
    }

    private fun githubToken(): String? = System.getenv("GITHUB_TOKEN")?.takeIf(String::isNotBlank)
}

/** Returns the standard content-addressed AOT compiler cache. */
internal fun defaultAotCompilerCacheDirectory(): File = File(
    System.getProperty("user.home"),
    ".wasmline/toolchains/wasmtime/compiler-assets",
)

/** Returns the default export for a Component Wasmline Service contract. */
internal fun defaultComponentServiceExport(): String = WasmlineComponentServiceContract.DEFAULT_EXPORT

/** Resolves one parsed CLI selection against the local catalog for the requested targets. */
internal fun resolveCliAotSelection(
    selection: AotCompatibilitySelection,
    targets: Collection<String>,
): AotCompatibilityResolution {
    val targetSpecs = WasmlineArtifactTargetFactory.create(targets)
    return AotCompatibilityCatalog.resolveSelection(
        selection = selection,
        artifactBackends = targetSpecs.map(WasmlineAotTargetSpec::artifactBackend).toSet(),
    )
}

/** Parses repeated --aot-generations values into the shared local selection model. */
internal fun parseCliAotSelection(values: Collection<String>): AotCompatibilitySelection =
    AotCompatibilitySelection(values.map(::parseAotGeneration))

/** Parses one point or closed FROM..THROUGH generation token without normalization. */
private fun parseAotGeneration(value: String): WasmlineVersionRange {
    val separatorIndex = value.indexOf(AOT_GENERATION_RANGE_SEPARATOR)
    if (separatorIndex < 0) return WasmlineVersionRange(value, value)
    val from = value.substring(0, separatorIndex)
    val through = value.substring(separatorIndex + AOT_GENERATION_RANGE_SEPARATOR.length)
    require(from.isNotEmpty() && through.isNotEmpty()) {
        "AOT generation range must use two non-empty endpoints: '$value'."
    }
    require(value.indexOf(AOT_GENERATION_RANGE_SEPARATOR, separatorIndex + AOT_GENERATION_RANGE_SEPARATOR.length) < 0) {
        "AOT generation range must contain exactly one separator: '$value'."
    }
    require(from != LATEST_AOT_GENERATION) {
        "AOT generation range must start at a concrete x.y.z version: '$value'."
    }
    return WasmlineVersionRange(from, through)
}

private const val LATEST_AOT_GENERATION: String = "latest"

private const val AOT_GENERATION_RANGE_SEPARATOR: String = ".."
