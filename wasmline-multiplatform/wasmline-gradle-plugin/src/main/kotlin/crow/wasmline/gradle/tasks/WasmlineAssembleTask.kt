@file:Suppress("SpellCheckingInspection")

package crow.wasmline.gradle.tasks

import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.plugin.core.component.ComponentBuildRecords
import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import crow.wasmline.plugin.core.manifest.ManifestSigner
import crow.wasmline.plugin.core.packaging.PluginPackager
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Assembles and signs a Core Wasm or raw Component Model plugin package. */
abstract class WasmlineAssembleTask : DefaultTask() {
    init {
        group = "wasmline"
        description = "Assemble wasmline plugin package"
    }

    @get:Input
    abstract val buildVariant: Property<String>

    @get:Input
    abstract val pluginId: Property<String>

    @get:Input
    abstract val pluginVersion: Property<String>

    @get:Input
    abstract val versionCode: Property<Long>

    @get:Input
    abstract val minSdkVersion: Property<String>

    @get:Input
    @get:Optional
    abstract val displayName: Property<String>

    @get:Input
    @get:Optional
    abstract val author: Property<String>

    @get:Input
    @get:Optional
    abstract val pluginDescription: Property<String>

    @get:Input
    @get:Optional
    abstract val iconUrl: Property<String>

    @get:Input
    @get:Optional
    abstract val homePageUrl: Property<String>

    @get:Input
    abstract val signingKey: Property<String>

    @get:Input
    abstract val metadata: MapProperty<String, String>

    @get:Input
    abstract val executionModel: Property<WasmlineExecutionModel>

    @get:Input
    abstract val invocationProtocol: Property<WasmlineInvocationProtocol>

    @get:Input
    @get:Optional
    abstract val exportName: Property<String>

    @get:Input
    abstract val contractMetadata: MapProperty<String, String>

    @get:Internal
    abstract val wasmtimeDirectory: DirectoryProperty

    @get:Input
    abstract val compileTargets: ListProperty<String>

    @get:Input
    abstract val wasmtimeVersion: Property<String>

    @get:InputDirectory
    @get:Optional
    abstract val wasmCompileOutputDir: DirectoryProperty

    /** Output from WasmlineComponentizeTask; only read for COMPONENT_MODEL. */
    @get:InputDirectory
    @get:Optional
    abstract val componentOutputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun assemble() {
        val variant = buildVariant.get()
        val id = pluginId.get()
        val version = pluginVersion.get()
        val packageDirectory = File(outputDir.get().asFile, id + "-" + version).apply { mkdirs() }
        val debugDirectory = File(packageDirectory, "debug").apply { mkdirs() }
        val productName = id.substringAfterLast('.')

        logger.info("Wasmline assemble: plugin=" + id + ", version=" + version + ", variant=" + variant)
        val prepared = when (executionModel.get()) {
            WasmlineExecutionModel.CORE_WASM -> prepareCoreArtifacts(packageDirectory, productName, variant)
            WasmlineExecutionModel.COMPONENT_MODEL -> prepareComponentArtifact(packageDirectory, debugDirectory)
        }
        val effectiveExportName = exportName.orNull ?: prepared.artifacts.singleOrNull()?.exportName

        WasmtimeCompiler().writeCompileResult(
            inputFile = prepared.inputFile,
            debugDir = debugDirectory,
            artifacts = prepared.artifacts,
            wasmtimeVersion = prepared.compilerVersion,
        )
        val manifestFile = ManifestSigner().createSignedManifest(
            artifacts = prepared.artifacts,
            pluginId = id,
            version = version,
            versionCode = versionCode.get(),
            minSdkVersion = minSdkVersion.get(),
            signingKey = signingKey.get(),
            outputDir = packageDirectory,
            displayName = displayName.orNull,
            author = author.orNull,
            description = pluginDescription.orNull,
            iconUrl = iconUrl.orNull,
            homePageUrl = homePageUrl.orNull,
            metadata = metadata.get(),
            executionModel = executionModel.get(),
            invocationProtocol = invocationProtocol.get(),
            exportName = effectiveExportName,
            contractMetadata = contractMetadata.get(),
            logger = { message -> logger.info(message) },
        )

        val distDirectory = File(outputDir.get().asFile.parentFile, "dist").apply { mkdirs() }
        val zipFile = File(distDirectory, id + "-" + version + ".zip")
        PluginPackager.createZip(
            manifestFile = manifestFile,
            artifacts = prepared.artifacts,
            artifactDirectory = packageDirectory,
            destination = zipFile,
            folderPrefix = id + "-" + version,
        )
        logger.lifecycle("Wasmline package: " + zipFile.absolutePath + " (" + zipFile.length() + " bytes)")
    }

    private fun prepareCoreArtifacts(
        packageDirectory: File,
        productName: String,
        variant: String,
    ): PreparedArtifacts {
        val compileDirectory = wasmCompileOutputDir.get().asFile
        val candidates = if (compileDirectory.isDirectory) {
            compileDirectory.walkTopDown()
                .filter { file -> file.isFile && file.extension.equals("wasm", ignoreCase = true) }
                .sortedBy { it.relativeTo(compileDirectory).invariantSeparatorsPath }
                .toList()
        } else {
            emptyList()
        }
        val wasmFile = candidates.firstOrNull { it.nameWithoutExtension == productName }
            ?: candidates.firstOrNull()
            ?: throw GradleException(
                "No .wasm file was found in " + compileDirectory.absolutePath +
                    " after the Kotlin/WasmWasi " + variant + " compilation.",
            )

        val wasmtimeBaseDirectory = wasmtimeDirectory.orNull?.asFile
            ?: System.getenv("WASMTIME_ROOT")?.let(::File)
            ?: File(System.getProperty("user.home"), ".wasmline/wasmtime")
        val executable = WasmtimeCompiler.findWasmtimeInDirectory(
            baseDir = wasmtimeBaseDirectory,
            version = wasmtimeVersion.get(),
        ) ?: throw GradleException(
            "wasmtime " + wasmtimeVersion.get() + " was not found in " + wasmtimeBaseDirectory.absolutePath + ". " +
                "Run './gradlew wasmlineDownloadWasmtime' first.",
        )
        val artifacts = WasmtimeCompiler().compileAll(
            wasmtimeExec = executable,
            inputWasm = wasmFile,
            outputDir = packageDirectory,
            productName = productName,
            targets = compileTargets.get(),
            wasmtimeVersion = wasmtimeVersion.get(),
            logger = ::logCompilerMessage,
        )
        if (artifacts.isEmpty()) {
            throw GradleException("No Core Wasm artifacts were produced by Wasmtime.")
        }
        return PreparedArtifacts(
            inputFile = wasmFile,
            artifacts = artifacts,
            compilerVersion = wasmtimeVersion.get(),
        )
    }

    private fun prepareComponentArtifact(
        packageDirectory: File,
        debugDirectory: File,
    ): PreparedArtifacts {
        val componentDirectory = componentOutputDirectory.orNull?.asFile
            ?: throw GradleException("Component output directory is not configured.")
        val resultFile = File(componentDirectory, ComponentBuildRecords.FILE_NAME)
        val record = try {
            ComponentBuildRecords.read(resultFile)
        } catch (error: Exception) {
            throw GradleException("Unable to read Component build result: " + error.message, error)
        }
        val sourceArtifact = record.toArtifact(componentDirectory)
        val sourceFile = record.resolveComponentFile(componentDirectory)
        val packagedFile = File(packageDirectory, sourceFile.name)
        Files.copy(sourceFile.toPath(), packagedFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(
            resultFile.toPath(),
            File(debugDirectory, ComponentBuildRecords.FILE_NAME).toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
        return PreparedArtifacts(
            inputFile = packagedFile,
            artifacts = listOf(sourceArtifact.copy(url = packagedFile.name)),
            compilerVersion = record.wasmToolsVersion,
        )
    }

    private fun logCompilerMessage(message: String) {
        if (message.contains("error", ignoreCase = true) || message.startsWith("Failed")) {
            logger.error(message)
        } else {
            logger.info(message)
        }
    }

    private data class PreparedArtifacts(
        val inputFile: File,
        val artifacts: List<WasmlineArtifact>,
        val compilerVersion: String,
    )
}
