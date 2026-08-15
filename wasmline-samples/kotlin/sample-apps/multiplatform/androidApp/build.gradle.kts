import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

abstract class WasmlineAndroidAssetSyncTask @Inject constructor(private val fileSystemOperations: FileSystemOperations) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputFile: RegularFileProperty

    @get:Input
    abstract val targetFileName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun syncAsset() {
        fileSystemOperations.sync {
            from(inputFile)
            into(outputDirectory)
            rename { targetFileName.get() }
        }
    }
}

plugins {
    alias(libs.plugins.app.base.android)
    alias(libs.plugins.jetbrains.compose.compiler)
}

val requestedArtifactFormat = providers.gradleProperty("wasmline.artifact.format")
    .orElse(providers.environmentVariable("WASMLINE_ARTIFACT_FORMAT"))
    .map { it.lowercase() }
    .orElse("pwasm64")
    .map { if (it == "pwasm") "pwasm64" else it }
    .get()
require(requestedArtifactFormat in setOf("pwasm32", "pwasm64", "cwasm")) {
    "Unsupported wasmline.artifact.format '$requestedArtifactFormat'. Expected pwasm32, pwasm64, or cwasm."
}

val requestedCwasmTarget = providers.gradleProperty("wasmline.compile.target")
    .orElse("aarch64-android")
    .get()

val samplePluginOutput = project(":sample-plugin").layout.buildDirectory.dir(
    "wasmline/output/crow.wasmline.demo-1.0.0",
)
val samplePluginArtifactName = when (requestedArtifactFormat) {
    "pwasm32" -> "demo-pulley32.pwasm"
    "pwasm64" -> "demo-pulley64.pwasm"
    "cwasm" -> "demo-$requestedCwasmTarget.cwasm"
    else -> error("Unsupported wasmline artifact format: $requestedArtifactFormat")
}
val samplePluginArtifactExtension = samplePluginArtifactName.substringAfterLast('.')
val generatedAssetDirectory = layout.buildDirectory.dir("generated/wasmline-assets")
val syncWasmlineSamplePlugin = tasks.register<WasmlineAndroidAssetSyncTask>("syncWasmlineSamplePlugin") {
    group = "wasmline"
    description = "Build and expose the selected Wasmline plugin artifact to Android assets"
    dependsOn(project(":sample-plugin").tasks.named("wasmlineAssembleDebug"))
    inputFile.set(samplePluginOutput.map { it.file(samplePluginArtifactName) })
    targetFileName.set("plugin.$samplePluginArtifactExtension")
    outputDirectory.set(generatedAssetDirectory)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncWasmlineSamplePlugin) {
            it.outputDirectory
        }
    }
}

tasks.matching { it.name == "mergeDebugAssets" }.configureEach {
    dependsOn(syncWasmlineSamplePlugin)
}

tasks.matching { it.name == "installDebug" }.configureEach {
    dependsOn(syncWasmlineSamplePlugin)
}

tasks.register<Exec>("wasmlineRunDebug") {
    group = "wasmline"
    description = "Install the Compose Android debug sample and launch it with adb"
    dependsOn("installDebug")

    val adbExecutable = providers.gradleProperty("adb.executable").orElse("adb")
    val androidDevice = providers.gradleProperty("android.device")
    doFirst {
        val command = mutableListOf(adbExecutable.get())
        androidDevice.orNull?.takeIf { it.isNotBlank() }?.let { serial ->
            command += listOf("-s", serial)
        }
        command += listOf("shell", "am", "start", "-n", "crow.wasmline/crow.wasmline.sample.MainActivity")
        commandLine(command)
    }
}

dependencies {
    implementation(projects.sampleApps.multiplatform.shared)
    implementation(libs.androidx.activity.compose)
}
