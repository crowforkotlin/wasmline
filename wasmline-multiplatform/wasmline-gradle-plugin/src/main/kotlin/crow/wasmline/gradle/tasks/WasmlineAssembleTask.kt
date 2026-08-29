package crow.wasmline.gradle.tasks

import crow.wasmline.plugin.core.aot.WasmlineAotBuildRecords
import crow.wasmline.plugin.core.diagnostics.WasmlineArtifactDiagnostics
import crow.wasmline.plugin.core.manifest.ManifestSigner
import crow.wasmline.plugin.core.manifest.ManifestSigningMain
import crow.wasmline.plugin.core.packaging.PluginPackager
import crow.wasmline.plugin.core.packaging.WasmlineDirectoryTransaction
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
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
import java.util.Properties
import javax.inject.Inject

/**
 * Signs and transactionally publishes one complete Wasmline package and offline ZIP.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
internal abstract class WasmlineAssembleTask @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
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
    abstract val buildTimestamp: Property<Long>

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

    @get:InputDirectory
    abstract val aotOutputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val distributionDirectory: DirectoryProperty

    @get:Classpath
    abstract val manifestToolClasspath: ConfigurableFileCollection

    @get:Internal
    abstract val assembleStateService: org.gradle.api.provider.Property<WasmlineAssembleStateService>

    init {
        group = "wasmline"
        description = "Assemble a signed Wasmline plugin package"
    }

    /** Creates the signed directory and ZIP without replacing the last success until completion. */
    @TaskAction
    fun assemble() {
        val id = pluginId.get()
        val version = pluginVersion.get()
        val folderName = "$id-$version"
        val destination = File(outputDirectory.get().asFile, folderName)
        val distribution = distributionDirectory.get().asFile.apply {
            check(isDirectory || mkdirs()) { "Unable to create distribution directory: $absolutePath" }
        }
        val finalZip = File(distribution, "$folderName.zip")
        val temporaryZip = Files.createTempFile(distribution.toPath(), ".$folderName-", ".zip").toFile()

        try {
            WasmlineDirectoryTransaction.create(destination).use { transaction ->
                val sourceDirectory = aotOutputDirectory.get().asFile
                val sourceRecordFile = File(sourceDirectory, WasmlineAotBuildRecords.FILE_NAME)
                val record = WasmlineAotBuildRecords.read(sourceRecordFile)
                WasmlineAotBuildRecords.materializeArtifacts(
                    record = record,
                    sourcePackageDirectory = sourceDirectory,
                    destinationPackageDirectory = transaction.stagingDirectory,
                )
                val manifestFile = createSignedManifest(
                    buildRecordFile = sourceRecordFile,
                    packageDirectory = transaction.stagingDirectory,
                )
                PluginPackager.createZip(
                    manifestFile = manifestFile,
                    buildRecord = record,
                    packageDirectory = transaction.stagingDirectory,
                    destination = temporaryZip,
                    folderPrefix = folderName,
                )
                transaction.commitWithFile(temporaryZip, finalZip)
                record.compiledOutputs.forEach { artifact ->
                    logger.lifecycle("Wasmline artifact: ${WasmlineArtifactDiagnostics.format(artifact, record)}")
                }
                logger.lifecycle("Wasmline package: ${finalZip.absolutePath} (${finalZip.length()} bytes)")
            }
        } catch (error: Exception) {
            throw GradleException("Unable to assemble Wasmline package '$folderName': ${error.message}", error)
        } finally {
            if (temporaryZip.exists()) temporaryZip.delete()
        }
        assembleStateService.orNull?.markSuccessful()
    }

    private fun createSignedManifest(buildRecordFile: File, packageDirectory: File): File {
        val requestFile = File(temporaryDir, "manifest-signing.properties")
        Properties().apply {
            setProperty(ManifestSigningMain.AOT_BUILD_RECORD_FILE, buildRecordFile.absolutePath)
            setProperty(ManifestSigningMain.OUTPUT_DIRECTORY, packageDirectory.absolutePath)
            setProperty(ManifestSigningMain.PLUGIN_ID, pluginId.get())
            setProperty(ManifestSigningMain.PLUGIN_VERSION, pluginVersion.get())
            setProperty(ManifestSigningMain.VERSION_CODE, versionCode.get().toString())
            setProperty(ManifestSigningMain.MIN_SDK_VERSION, minSdkVersion.get())
            setProperty(ManifestSigningMain.BUILD_TIMESTAMP, buildTimestamp.get().toString())
            setProperty(ManifestSigningMain.SIGNING_KEY, signingKey.get())
            setOptional(ManifestSigningMain.DISPLAY_NAME, displayName.orNull)
            setOptional(ManifestSigningMain.AUTHOR, author.orNull)
            setOptional(ManifestSigningMain.DESCRIPTION, pluginDescription.orNull)
            setOptional(ManifestSigningMain.ICON_URL, iconUrl.orNull)
            setOptional(ManifestSigningMain.HOME_PAGE_URL, homePageUrl.orNull)
            metadata.get().forEach { (key, value) -> setProperty(ManifestSigningMain.METADATA_PREFIX + key, value) }
            requestFile.outputStream().use { output -> store(output, null) }
        }
        execOperations.javaexec { spec ->
            spec.classpath(manifestToolClasspath)
            spec.mainClass.set(ManifestSigningMain::class.java.name)
            spec.args(requestFile.absolutePath)
        }.assertNormalExitValue()
        return File(packageDirectory, ManifestSigner.DEFAULT_MANIFEST_NAME).also { manifest ->
            require(manifest.isFile) { "Manifest signer did not create ${manifest.absolutePath}." }
        }
    }

    private fun Properties.setOptional(name: String, value: String?) {
        if (value != null) setProperty(name, value)
    }
}
