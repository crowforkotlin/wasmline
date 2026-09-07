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
import com.github.ajalt.clikt.parameters.types.int
import crow.wasmline.WasmlineComponentServiceContract
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineRuntimeContract
import crow.wasmline.plugin.core.aot.WasmlineAotBuildRecords
import crow.wasmline.plugin.core.diagnostics.WasmlineArtifactDiagnostics
import crow.wasmline.plugin.core.packaging.WasmlineDirectoryTransaction
import crow.wasmline.plugin.core.toolchain.ToolchainCatalog
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Compiles Core Wasm or a raw Component into a catalog-backed AOT artifact matrix.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
internal class Compile : CliktCommand(name = "compile") {
    private val inputFile by option("-i", "--input")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
        .required()
    private val name by option("-n", "--name")
    private val version by option("-v", "--version").default("1.0.0")
    private val outputRoot by option("-o", "--output")
        .file(canBeFile = false, canBeDir = true)
        .default(File("build/wasmline/output"))
    private val targets by option("-t", "--target").multiple().unique()
    private val aotCompatibility by option("--aot-compatibility")
    private val aotVersionRanges by option("--aot-version-range").multiple().unique()
    private val compilerCache by option("--aot-compiler-cache")
        .file(canBeFile = false, canBeDir = true)
        .default(defaultAotCompilerCacheDirectory())
    private val autoDownload by option("--auto-download").flag(default = false)
    private val maxParallelCompilations by option("--max-parallel-compilations")
        .int()
        .default(maxOf(1, Runtime.getRuntime().availableProcessors() / 2))
    private val executionModel by option("--execution-model").default(WasmlineExecutionModel.CORE_WASM.name)
    private val invocationProtocol by option("--invocation-protocol")
    private val exportName by option("--export-name")
    private val contractMetadata by option("--contract-metadata").multiple().unique()
    private val rawAbiMetadata by option("--raw-abi-metadata")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
    private val codec by option("--codec").default(WasmlineComponentServiceContract.DEFAULT_CODEC)
    private val serviceProtocolVersion by option("--service-version").default(WasmlineComponentServiceContract.VERSION)
    private val rawComponent by option("--raw-component").flag(default = false)
    private val witPath by option("--wit").file(mustExist = true, canBeFile = true, canBeDir = true)
    private val world by option("--world")
    private val adapterPath by option("--adapter").file(mustExist = true, canBeFile = true, canBeDir = false)
    private val wasmToolsPath by option("--wasm-tools").file(mustExist = true, canBeFile = true, canBeDir = false)
    private val toolCache by option("--tool-cache")
        .file(canBeFile = false, canBeDir = true)
        .default(defaultToolCacheDirectory())
    private val wasmToolsVersion by option("--wasm-tools-version").default(ToolchainCatalog.WASM_TOOLS_VERSION)
    private val minSdkVersion by option("--min-sdk").default(BuildConfig.VERSION)

    override fun run() = runBlocking {
        val productName = name ?: inputFile.nameWithoutExtension
        val destination = File(outputRoot, "$productName-$version")
        try {
            val invocation = resolveInvocation()
            val aotSelection = parseCliAotSelection(aotCompatibility, aotVersionRanges)
            val support = CliAotBuildSupport(::echo)
            WasmlineDirectoryTransaction.create(destination).use { transaction ->
                val componentInputDirectory = File(transaction.stagingDirectory, ".component-input")
                val prepared = when (invocation.executionModel) {
                    WasmlineExecutionModel.CORE_WASM -> CliPreparedAotInput(
                        file = inputFile,
                        runtimeContract = WasmlineRuntimeContract(
                            executionModel = invocation.executionModel,
                            invocationProtocol = invocation.invocationProtocol,
                            exportName = invocation.exportName,
                            contractMetadata = invocation.contractMetadata,
                            rawAbi = invocation.rawAbi,
                        ),
                    )

                    WasmlineExecutionModel.COMPONENT_MODEL -> support.prepareComponent(
                        CliComponentPreparationRequest(
                            inputFile = inputFile,
                            outputDirectory = componentInputDirectory,
                            productName = productName,
                            invocation = invocation,
                            inputIsRawComponent = rawComponent,
                            witPath = witPath,
                            world = world,
                            adapterPath = adapterPath,
                            wasmToolsPath = wasmToolsPath,
                            toolCacheDirectory = toolCache,
                            wasmToolsVersion = wasmToolsVersion,
                            codec = codec,
                            serviceProtocolVersion = serviceProtocolVersion,
                        ),
                    )
                }
                val workingDirectory = File(transaction.stagingDirectory, ".aot-working")
                val record = support.buildMatrix(
                    input = prepared,
                    packageDirectory = transaction.stagingDirectory,
                    workingDirectory = workingDirectory,
                    targets = targets,
                    selection = aotSelection,
                    minSdkVersion = minSdkVersion,
                    publishRawWasm = invocation.executionModel == WasmlineExecutionModel.CORE_WASM,
                    compilerCacheDirectory = compilerCache,
                    autoDownload = autoDownload,
                    maxParallelCompilations = maxParallelCompilations,
                )
                componentInputDirectory.deleteRecursively()
                workingDirectory.deleteRecursively()
                val recordFile = WasmlineAotBuildRecords.write(
                    record,
                    File(transaction.stagingDirectory, WasmlineAotBuildRecords.FILE_NAME),
                )
                transaction.commit()
                record.compiledOutputs.forEach { output ->
                    echo("Wasmline artifact: ${WasmlineArtifactDiagnostics.format(output, record)}")
                }
                echo("AOT build record written to: ${File(destination, recordFile.name).absolutePath}")
            }
        } catch (error: Exception) {
            echo("Error: ${error.message}", err = true)
            throw ProgramResult(1)
        }
    }

    private fun resolveInvocation(): InvocationOptions {
        val componentBuild = executionModel.equals(WasmlineExecutionModel.COMPONENT_MODEL.name, ignoreCase = true)
        val protocol = invocationProtocol ?: if (componentBuild) {
            WasmlineInvocationProtocol.COMPONENT_EXPORT.name
        } else {
            WasmlineInvocationProtocol.WASMLINE_SERVICE.name
        }
        val selectedExport = exportName ?: if (
            componentBuild && protocol.equals(WasmlineInvocationProtocol.WASMLINE_SERVICE.name, ignoreCase = true)
        ) {
            defaultComponentServiceExport()
        } else {
            null
        }
        return parseInvocationOptions(executionModel, protocol, selectedExport, contractMetadata, rawAbiMetadata)
    }
}
