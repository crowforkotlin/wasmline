import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.compose.compiler)
}

android {
    namespace = "crow.wasmline.samples.minimal.rawexport"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "crow.wasmline.samples.minimal.rawexport"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = rootProject.extra["sampleVersion"] as String
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(rootProject.extra["jbrVersion"] as Int)
        targetCompatibility = JavaVersion.toVersion(rootProject.extra["jbrVersion"] as Int)
    }
}

abstract class PluginAssetsTask @Inject constructor(private val fileSystemOperations: FileSystemOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packageDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copyPackage() {
        fileSystemOperations.sync {
            from(packageDirectory) {
                include("manifest.wlm", "artifacts/**")
                into("plugin")
            }
            into(outputDirectory)
        }
    }
}

@Suppress("UNCHECKED_CAST")
val pluginPackage = rootProject.extra["pluginPackage"] as Provider<Directory>
val syncPluginAssets = tasks.register<PluginAssetsTask>("syncPluginAssets") {
    dependsOn(project(projects.plugin.path).tasks.named("wasmlineAssembleDebug"))
    packageDirectory.set(pluginPackage)
    outputDirectory.set(layout.buildDirectory.dir("generated/plugin-assets"))
}
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncPluginAssets) { it.outputDirectory }
    }
}

dependencies {
    implementation(projects.common)
    implementation(libs.androidx.activity.compose)
}
