package crow.wasmline.cli

import crow.wasmline.WasmlineComponentServiceContract
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.loader.model.WasmlineRuntimeContract
import crow.wasmline.plugin.core.aot.WasmlineAotBuildRecord
import crow.wasmline.plugin.core.aot.WasmlineAotBuildRequest
import crow.wasmline.plugin.core.aot.WasmlineAotBuildService
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
 * Date: 2026-08-28
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
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
internal data class CliPreparedAotInput(val file: File, val runtimeContract: WasmlineRuntimeContract)

/**
 * Adapts CLI inputs to the shared Component and AOT build services.
 *
 * Date: 2026-08-28
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

    /** Builds one complete catalog profile and target matrix. */
    suspend fun buildMatrix(
        input: CliPreparedAotInput,
        packageDirectory: File,
        workingDirectory: File,
        targets: Collection<String>,
        wasmtimeVersions: Collection<String>,
        profileIds: Collection<String>,
        publishRawWasm: Boolean,
        compilerCacheDirectory: File,
        autoDownload: Boolean,
        maxParallelCompilations: Int,
    ): WasmlineAotBuildRecord = WasmlineAotBuildService().build(
        WasmlineAotBuildRequest(
            inputFile = input.file,
            packageDirectory = packageDirectory,
            workingDirectory = workingDirectory,
            runtimeContract = input.runtimeContract,
            targets = targets,
            wasmtimeVersions = wasmtimeVersions,
            aotCompatibilityProfileIds = profileIds,
            publishRawWasm = publishRawWasm,
            compilerCacheDirectory = compilerCacheDirectory,
            buildHost = PlatformDetector.detectPlatform(),
            autoDownload = autoDownload,
            githubToken = githubToken(),
            maxParallelCompilations = maxParallelCompilations,
            logger = logger,
        ),
    )

    private fun githubToken(): String? = System.getenv("GITHUB_TOKEN")?.takeIf(String::isNotBlank)
}

/** Returns the standard content-addressed AOT compiler cache. */
internal fun defaultAotCompilerCacheDirectory(): File = File(
    System.getProperty("user.home"),
    ".wasmline/toolchains/wasmtime/compiler-assets",
)

/** Returns the default export for a Component Wasmline Service contract. */
internal fun defaultComponentServiceExport(): String = WasmlineComponentServiceContract.DEFAULT_EXPORT
