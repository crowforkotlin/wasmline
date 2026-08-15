@file:Suppress("SpellCheckingInspection")

package crow.wasmline.gradle.tasks

import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import crow.wasmline.plugin.core.component.ComponentAotBuildRecords
import crow.wasmline.plugin.core.diagnostics.WasmlineArtifactDiagnostics
import crow.wasmline.plugin.core.manifest.ManifestSigner
import crow.wasmline.plugin.core.manifest.ManifestSigningMain
import crow.wasmline.plugin.core.packaging.PluginPackager
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import javax.inject.Inject

/** Assembles and signs a Core Wasm or native Component Model plugin package. */
internal abstract class WasmlineAssembleTask @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
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

    /** Output from WasmlineComponentAotTask; only read for COMPONENT_MODEL. */
    @get:InputDirectory
    @get:Optional
    abstract val componentOutputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Classpath
    abstract val manifestToolClasspath: ConfigurableFileCollection

    @TaskAction
    fun assemble() {
        val variant = buildVariant.get()
        val id = pluginId.get()
        val version = pluginVersion.get()
        val packageDirectory = File(outputDir.get().asFile, id + "-" + version)
        if (packageDirectory.exists() && !packageDirectory.deleteRecursively()) {
            throw GradleException("Unable to clean stale Wasmline package output: ${packageDirectory.absolutePath}")
        }
        if (!packageDirectory.mkdirs()) {
            throw GradleException("Unable to create Wasmline package output: ${packageDirectory.absolutePath}")
        }
        val debugDirectory = File(packageDirectory, "debug").apply { mkdirs() }
        val productName = id.substringAfterLast('.')

        logger.info("Wasmline assemble: plugin=" + id + ", version=" + version + ", variant=" + variant)
        val prepared = when (executionModel.get()) {
            WasmlineExecutionModel.CORE_WASM -> prepareCoreArtifacts(packageDirectory, productName, variant)
            WasmlineExecutionModel.COMPONENT_MODEL -> prepareComponentArtifacts(packageDirectory, debugDirectory)
        }
        val effectiveExportName = exportName.orNull ?: prepared.artifacts.singleOrNull()?.exportName

        WasmtimeCompiler().writeCompileResult(
            inputFile = prepared.inputFile,
            debugDir = debugDirectory,
            artifacts = prepared.artifacts,
            wasmtimeVersion = prepared.compilerVersion,
        )
        val compileResultFile = File(debugDirectory, WasmtimeCompiler.COMPILE_RESULT_FILE)
        val manifestFile = createSignedManifest(
            compileResultFile = compileResultFile,
            pluginId = id,
            version = version,
            exportName = effectiveExportName,
            packageDirectory = packageDirectory,
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
        prepared.artifacts.forEach { artifact ->
            logger.lifecycle("Wasmline artifact: " + WasmlineArtifactDiagnostics.format(artifact))
        }
        logger.lifecycle("Wasmline package: " + zipFile.absolutePath + " (" + zipFile.length() + " bytes)")
    }

    private fun createSignedManifest(
        compileResultFile: File,
        pluginId: String,
        version: String,
        exportName: String?,
        packageDirectory: File,
    ): File {
        val requestFile = File(temporaryDir, "manifest-signing.properties")
        Properties().apply {
            setProperty(ManifestSigningMain.COMPILE_RESULT_FILE, compileResultFile.absolutePath)
            setProperty(ManifestSigningMain.OUTPUT_DIRECTORY, packageDirectory.absolutePath)
            setProperty(ManifestSigningMain.PLUGIN_ID, pluginId)
            setProperty(ManifestSigningMain.PLUGIN_VERSION, version)
            setProperty(ManifestSigningMain.VERSION_CODE, versionCode.get().toString())
            setProperty(ManifestSigningMain.MIN_SDK_VERSION, minSdkVersion.get())
            setProperty(ManifestSigningMain.SIGNING_KEY, signingKey.get())
            setOptional(ManifestSigningMain.DISPLAY_NAME, displayName.orNull)
            setOptional(ManifestSigningMain.AUTHOR, author.orNull)
            setOptional(ManifestSigningMain.DESCRIPTION, pluginDescription.orNull)
            setOptional(ManifestSigningMain.ICON_URL, iconUrl.orNull)
            setOptional(ManifestSigningMain.HOME_PAGE_URL, homePageUrl.orNull)
            setProperty(ManifestSigningMain.EXECUTION_MODEL, executionModel.get().name)
            setProperty(ManifestSigningMain.INVOCATION_PROTOCOL, invocationProtocol.get().name)
            setOptional(ManifestSigningMain.EXPORT_NAME, exportName)
            metadata.get().forEach { (key, value) -> setProperty(ManifestSigningMain.METADATA_PREFIX + key, value) }
            contractMetadata.get().forEach { (key, value) ->
                setProperty(ManifestSigningMain.CONTRACT_METADATA_PREFIX + key, value)
            }
            requestFile.outputStream().use { output -> store(output, null) }
        }

        execOperations.javaexec { spec ->
            spec.classpath(manifestToolClasspath)
            spec.mainClass.set(ManifestSigningMain::class.java.name)
            spec.args(requestFile.absolutePath)
        }.assertNormalExitValue()

        return File(packageDirectory, ManifestSigner.DEFAULT_MANIFEST_NAME).also { manifest ->
            if (!manifest.isFile) throw GradleException("Manifest signer did not create ${manifest.absolutePath}.")
        }
    }

    private fun Properties.setOptional(name: String, value: String?) {
        if (value != null) setProperty(name, value)
    }

    private fun prepareCoreArtifacts(packageDirectory: File, productName: String, variant: String): PreparedArtifacts {
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
        val compilerVersion = WasmtimeCompiler.detectWasmtimeVersion(executable)
            ?: throw GradleException(
                "Unable to determine the exact Wasmtime version from ${executable.absolutePath}.",
            )
        val artifacts = WasmtimeCompiler().compileAll(
            wasmtimeExec = executable,
            inputWasm = wasmFile,
            outputDir = packageDirectory,
            productName = productName,
            targets = compileTargets.get(),
            wasmtimeVersion = compilerVersion,
            logger = ::logCompilerMessage,
        )
        if (artifacts.isEmpty()) {
            throw GradleException("No Core Wasm artifacts were produced by Wasmtime.")
        }
        return PreparedArtifacts(
            inputFile = wasmFile,
            artifacts = artifacts,
            compilerVersion = compilerVersion,
        )
    }

    private fun prepareComponentArtifacts(packageDirectory: File, debugDirectory: File): PreparedArtifacts {
        val componentDirectory = componentOutputDirectory.orNull?.asFile
            ?: throw GradleException("Component AOT output directory is not configured.")
        val resultFile = File(componentDirectory, ComponentAotBuildRecords.FILE_NAME)
        val (record, artifacts) = try {
            val record = ComponentAotBuildRecords.read(resultFile)
            record to ComponentAotBuildRecords.materializeArtifacts(
                record = record,
                sourceDirectory = componentDirectory,
                destinationDirectory = packageDirectory,
            )
        } catch (error: Exception) {
            throw GradleException("Unable to prepare Component AOT build result: " + error.message, error)
        }
        Files.copy(
            resultFile.toPath(),
            File(debugDirectory, ComponentAotBuildRecords.FILE_NAME).toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
        return PreparedArtifacts(
            inputFile = File(packageDirectory, artifacts.first().url),
            artifacts = artifacts,
            compilerVersion = record.wasmtimeVersion,
        )
    }

    private fun logCompilerMessage(message: String) {
        if (message.contains("error", ignoreCase = true) || message.startsWith("Failed")) {
            logger.error(message)
        } else {
            logger.info(message)
        }
    }

    private data class PreparedArtifacts(val inputFile: File, val artifacts: List<WasmlineArtifact>, val compilerVersion: String)
}
