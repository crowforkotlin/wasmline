package crow.wasmline.gradle.tasks

import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineRuntimeContract
import crow.wasmline.plugin.core.aot.WasmlineAotBuildRecords
import crow.wasmline.plugin.core.aot.WasmlineAotBuildRequest
import crow.wasmline.plugin.core.aot.WasmlineAotBuildService
import crow.wasmline.plugin.core.aot.WasmlineRawAbiMetadataCodec
import crow.wasmline.plugin.core.component.ComponentBuildRecords
import crow.wasmline.plugin.core.packaging.WasmlineDirectoryTransaction
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Builds the catalog profile and compatible-target matrix for Core or Component input.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@CacheableTask
internal abstract class WasmlineAotBuildTask : DefaultTask() {
    @get:Input
    abstract val productName: Property<String>

    @get:Input
    abstract val executionModel: Property<WasmlineExecutionModel>

    @get:Input
    abstract val invocationProtocol: Property<WasmlineInvocationProtocol>

    @get:Input
    @get:Optional
    abstract val exportName: Property<String>

    @get:Input
    abstract val contractMetadata: MapProperty<String, String>

    @get:Input
    @get:Optional
    abstract val rawAbiMetadataJson: Property<String>

    @get:Input
    abstract val targets: ListProperty<String>

    @get:Input
    abstract val wasmtimeVersions: ListProperty<String>

    @get:Input
    abstract val aotCompatibilityProfileIds: ListProperty<String>

    @get:Input
    abstract val autoDownload: Property<Boolean>

    @get:Input
    abstract val buildHost: Property<String>

    @get:Input
    abstract val maxParallelCompilations: Property<Int>

    @get:Internal
    abstract val githubToken: Property<String>

    @get:Internal
    abstract val compilerCacheDirectory: DirectoryProperty

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val coreWasmCompileOutputDirectory: DirectoryProperty

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val componentOutputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        group = "wasmline"
        description = "Build the Wasmline multi-profile AOT artifact matrix"
    }

    /** Builds every requested matrix unit and publishes one complete intermediate record. */
    @TaskAction
    fun buildAotMatrix() {
        val model = executionModel.get()
        val (input, contract) = when (model) {
            WasmlineExecutionModel.CORE_WASM -> resolveCoreInput() to coreRuntimeContract()
            WasmlineExecutionModel.COMPONENT_MODEL -> resolveComponentInput()
        }
        val destination = outputDirectory.get().asFile
        try {
            WasmlineDirectoryTransaction.create(destination).use { transaction ->
                val staging = transaction.stagingDirectory
                val working = File(staging, ".working")
                val record = runBlocking {
                    WasmlineAotBuildService().build(
                        WasmlineAotBuildRequest(
                            inputFile = input,
                            packageDirectory = staging,
                            workingDirectory = working,
                            runtimeContract = contract,
                            targets = targets.get(),
                            wasmtimeVersions = wasmtimeVersions.get(),
                            aotCompatibilityProfileIds = aotCompatibilityProfileIds.get(),
                            publishRawWasm = model == WasmlineExecutionModel.CORE_WASM,
                            compilerCacheDirectory = compilerCacheDirectory.get().asFile,
                            buildHost = buildHost.get(),
                            autoDownload = autoDownload.get(),
                            githubToken = githubToken.orNull,
                            maxParallelCompilations = maxParallelCompilations.get(),
                            logger = { message -> logger.info(message) },
                        ),
                    )
                }
                if (working.exists()) {
                    check(working.deleteRecursively()) {
                        "Unable to remove AOT working directory: ${working.absolutePath}"
                    }
                }
                WasmlineAotBuildRecords.write(record, File(staging, WasmlineAotBuildRecords.FILE_NAME))
                transaction.commit()
                logger.lifecycle(
                    "Wasmline AOT matrix: ${record.resolvedProfiles.size} profiles, " +
                        "${record.compiledOutputs.size} compiled outputs",
                )
            }
        } catch (error: Exception) {
            throw GradleException("Unable to build the Wasmline AOT matrix: ${error.message}", error)
        }
    }

    private fun resolveCoreInput(): File {
        val directory = coreWasmCompileOutputDirectory.orNull?.asFile
            ?: throw GradleException("Core Wasm compile output directory is not configured.")
        val candidates = directory.walkTopDown()
            .filter { file -> file.isFile && file.extension.equals("wasm", ignoreCase = true) }
            .sortedBy { file -> file.relativeTo(directory).invariantSeparatorsPath }
            .toList()
        val exact = candidates.filter { it.nameWithoutExtension == productName.get() }
        return when {
            exact.size == 1 -> exact.single()

            exact.size > 1 -> throw GradleException("Multiple Core Wasm inputs match '${productName.get()}'.")

            candidates.size == 1 -> candidates.single()

            else -> throw GradleException(
                "Unable to select one Core Wasm input from ${directory.absolutePath}; " +
                    "found ${candidates.size} .wasm files.",
            )
        }
    }

    private fun coreRuntimeContract(): WasmlineRuntimeContract = WasmlineRuntimeContract(
        executionModel = WasmlineExecutionModel.CORE_WASM,
        invocationProtocol = invocationProtocol.get(),
        exportName = exportName.orNull,
        contractMetadata = contractMetadata.get(),
        rawAbi = rawAbiMetadataJson.orNull?.let(WasmlineRawAbiMetadataCodec::decode),
    )

    private fun resolveComponentInput(): Pair<File, WasmlineRuntimeContract> {
        val directory = componentOutputDirectory.orNull?.asFile
            ?: throw GradleException("Component build output directory is not configured.")
        val record = ComponentBuildRecords.read(File(directory, ComponentBuildRecords.FILE_NAME))
        require(record.invocationProtocol == invocationProtocol.get()) {
            "Component build invocation protocol does not match the manifest configuration."
        }
        require(exportName.orNull == null || exportName.get() == record.exportName) {
            "Component build export name does not match the manifest configuration."
        }
        val generatedContract = record.runtimeContract()
        val configuredMetadata = contractMetadata.get()
        val conflicts = configuredMetadata.keys.filter { key ->
            key in generatedContract.contractMetadata && configuredMetadata[key] != generatedContract.contractMetadata[key]
        }
        require(conflicts.isEmpty()) {
            "Component contract metadata conflicts with generated values: ${conflicts.sorted().joinToString()}."
        }
        return record.validateComponentFile(directory) to generatedContract.copy(
            contractMetadata = generatedContract.contractMetadata + configuredMetadata,
            rawAbi = rawAbiMetadataJson.orNull?.let(WasmlineRawAbiMetadataCodec::decode),
        )
    }
}
