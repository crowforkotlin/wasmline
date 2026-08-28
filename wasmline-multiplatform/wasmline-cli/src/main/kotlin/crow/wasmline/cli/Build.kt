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
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import crow.wasmline.WasmlineComponentServiceContract
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineRuntimeContract
import crow.wasmline.plugin.core.diagnostics.WasmlineArtifactDiagnostics
import crow.wasmline.plugin.core.manifest.ManifestSigner
import crow.wasmline.plugin.core.manifest.WasmlineManifestSigningRequest
import crow.wasmline.plugin.core.packaging.PluginPackager
import crow.wasmline.plugin.core.packaging.WasmlineDirectoryTransaction
import crow.wasmline.plugin.core.toolchain.ToolchainCatalog
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/**
 * Builds, signs, and packages one Core Wasm or Component Model plugin.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
internal class Build : CliktCommand(name = "build") {
    private val inputFile by option("-i", "--input")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
        .required()
    private val name by option("-n", "--name")
    private val targets by option("-t", "--target").multiple().unique()
    private val aotWasmtimeVersions by option("--aot-wasmtime-version").multiple().unique()
    private val aotProfileIds by option("--aot-compatibility-profile-id").multiple().unique()
    private val compilerCache by option("--aot-compiler-cache")
        .file(canBeFile = false, canBeDir = true)
        .default(defaultAotCompilerCacheDirectory())
    private val autoDownload by option("--auto-download").flag(default = false)
    private val maxParallelCompilations by option("--max-parallel-compilations")
        .int()
        .default(maxOf(1, Runtime.getRuntime().availableProcessors() / 2))
    private val pluginId by option("--plugin-id")
    private val version by option("-v", "--version").default("1.0.0")
    private val versionCode by option("--version-code").long().default(1L)
    private val minSdkVersion by option("--min-sdk").default(BuildConfig.VERSION)
    private val buildTimestamp by option("--build-timestamp").long().default(0L)
    private val displayName by option("--display-name")
    private val author by option("--author")
    private val description by option("--description")
    private val iconUrl by option("--icon-url")
    private val homeUrl by option("--home-url")
    private val metadata by option("--metadata").multiple().unique()
    private val executionModel by option("--execution-model").default(WasmlineExecutionModel.CORE_WASM.name)
    private val invocationProtocol by option("--invocation-protocol")
    private val exportName by option("--export-name")
    private val codec by option("--codec").default(WasmlineComponentServiceContract.DEFAULT_CODEC)
    private val serviceProtocolVersion by option("--service-version").default(WasmlineComponentServiceContract.VERSION)
    private val rawComponent by option("--raw-component").flag(default = false)
    private val contractMetadata by option("--contract-metadata").multiple().unique()
    private val rawAbiMetadata by option("--raw-abi-metadata")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
    private val key by option("-k", "--key").required().help("Ed25519 private key: file path or hex string")
    private val witPath by option("--wit").file(mustExist = true, canBeFile = true, canBeDir = true)
    private val world by option("--world")
    private val adapterPath by option("--adapter").file(mustExist = true, canBeFile = true, canBeDir = false)
    private val wasmToolsPath by option("--wasm-tools").file(mustExist = true, canBeFile = true, canBeDir = false)
    private val toolCache by option("--tool-cache")
        .file(canBeFile = false, canBeDir = true)
        .default(defaultToolCacheDirectory())
    private val wasmToolsVersion by option("--wasm-tools-version").default(ToolchainCatalog.WASM_TOOLS_VERSION)

    override fun run() = runBlocking {
        val productName = name ?: inputFile.nameWithoutExtension
        val packageId = pluginId ?: productName
        val folderName = "$packageId-$version"
        val destination = File("build/wasmline/output", folderName)
        val distributionDirectory = File("build/wasmline/dist").apply { mkdirs() }
        val finalZip = File(distributionDirectory, "$folderName.zip")
        val temporaryZip = Files.createTempFile(distributionDirectory.toPath(), ".$folderName-", ".zip").toFile()
        try {
            val invocation = resolveInvocation()
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
                        componentPreparationRequest(componentInputDirectory, productName, invocation),
                    )
                }
                val workingDirectory = File(transaction.stagingDirectory, ".aot-working")
                val record = support.buildMatrix(
                    input = prepared,
                    packageDirectory = transaction.stagingDirectory,
                    workingDirectory = workingDirectory,
                    targets = targets,
                    wasmtimeVersions = aotWasmtimeVersions,
                    profileIds = aotProfileIds,
                    publishRawWasm = invocation.executionModel == WasmlineExecutionModel.CORE_WASM,
                    compilerCacheDirectory = compilerCache,
                    autoDownload = autoDownload,
                    maxParallelCompilations = maxParallelCompilations,
                )
                componentInputDirectory.deleteRecursively()
                workingDirectory.deleteRecursively()
                val manifestFile = ManifestSigner().createSignedManifest(
                    WasmlineManifestSigningRequest(
                        buildRecord = record,
                        pluginId = packageId,
                        version = version,
                        versionCode = versionCode,
                        minSdkVersion = minSdkVersion,
                        buildTimestamp = buildTimestamp,
                        signingKey = key,
                        outputDirectory = transaction.stagingDirectory,
                        displayName = displayName,
                        author = author,
                        description = description,
                        iconUrl = iconUrl,
                        homePageUrl = homeUrl,
                        metadata = parseKeyValueEntries(metadata, "Metadata"),
                        logger = ::echo,
                    ),
                )
                PluginPackager.createZip(
                    manifestFile = manifestFile,
                    buildRecord = record,
                    packageDirectory = transaction.stagingDirectory,
                    destination = temporaryZip,
                    folderPrefix = folderName,
                )
                transaction.commitWithFile(temporaryZip, finalZip)
                record.compiledOutputs.forEach { output ->
                    echo("Wasmline artifact: ${WasmlineArtifactDiagnostics.format(output, record)}")
                }
                echo("Package written to: ${finalZip.absolutePath} (${finalZip.length()} bytes)")
            }
        } catch (error: Exception) {
            echo("Error: ${error.message}", err = true)
            throw ProgramResult(1)
        } finally {
            if (temporaryZip.exists()) temporaryZip.delete()
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

    private fun componentPreparationRequest(
        outputDirectory: File,
        productName: String,
        invocation: InvocationOptions,
    ): CliComponentPreparationRequest = CliComponentPreparationRequest(
        inputFile = inputFile,
        outputDirectory = outputDirectory,
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
    )
}

/** Parses unique key=value CLI entries. */
internal fun parseKeyValueEntries(entries: Collection<String>, label: String): Map<String, String> = entries.associate { entry ->
    val separator = entry.indexOf('=')
    require(separator > 0) { "$label must use key=value: $entry" }
    entry.substring(0, separator) to entry.substring(separator + 1)
}
