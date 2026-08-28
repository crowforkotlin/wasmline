import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

abstract class WasmlineAndroidAssetSyncTask @Inject constructor(private val fileSystemOperations: FileSystemOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun syncAsset() {
        fileSystemOperations.sync {
            from(inputDirectory) {
                include("manifest.wlm", "artifacts/**")
            }
            into(outputDirectory)
        }
    }
}

plugins {
    alias(libs.plugins.app.base.android)
    alias(libs.plugins.jetbrains.compose.compiler)
}

val samplePluginOutput = project(":sample-plugin").layout.buildDirectory.dir(
    "wasmline/output/crow.wasmline.demo-1.0.0",
)
val generatedAssetDirectory = layout.buildDirectory.dir("generated/wasmline-assets")
val syncWasmlineSamplePlugin = tasks.register<WasmlineAndroidAssetSyncTask>("syncWasmlineSamplePlugin") {
    group = "wasmline"
    description = "Build and expose the signed Wasmline plugin package to Android assets"
    dependsOn(project(":sample-plugin").tasks.named("wasmlineAssembleDebug"))
    inputDirectory.set(samplePluginOutput)
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
