import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.jetbrains.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.jvm)
}

compose.desktop {
    application {
        mainClass = "crow.wasmline.sample.MainKt"

        nativeDistributions {
            targetFormats(
                TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg, TargetFormat.Msi,
                TargetFormat.AppImage, TargetFormat.Exe, TargetFormat.Pkg
            )
            packageName = "Wasmline"
            packageVersion = project.findProperty("wasmline.version") as? String ?: "1.0.0"
            description = "Wasmline Sample Desktop Application"
            copyright = "© 2026 Wasmline. All rights reserved."
            vendor = "Wasmline"
            licenseFile.set(project.file("../../../../../LICENSE"))
            appResourcesRootDir.set(project.layout.projectDirectory.dir("appIcons"))

            linux {
                iconFile.set(project.file("appIcons/LinuxIcon.png"))
            }
            macOS {
                iconFile.set(project.file("appIcons/MacosIcon.icns"))
                bundleID = "crow.wasmline.sample"
            }
            windows {
                iconFile.set(project.file("appIcons/WindowsIcon.ico"))
                dirChooser = true
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }
        }
    }
}

dependencies {
    implementation(projects.sampleApps.multiplatform.shared)
}

val wasmlineEngine = providers.gradleProperty("wasmline.engine")
    .map { it.lowercase() }
    .orElse("pulley")
    .get()
require(wasmlineEngine in setOf("pulley", "cranelift")) {
    "Unsupported wasmline.engine '$wasmlineEngine'. Expected pulley or cranelift."
}

val requestedArtifactFormat = providers.gradleProperty("wasmline.artifact.format")
    .orElse(providers.environmentVariable("WASMLINE_ARTIFACT_FORMAT"))
    .map { it.lowercase() }
    .orElse(if (wasmlineEngine == "cranelift") "cwasm" else "pwasm64")
    .map { if (it == "pwasm") "pwasm64" else it }
    .get()
require(requestedArtifactFormat in setOf("pwasm64", "cwasm")) {
    "Desktop supports pwasm64 or cwasm artifacts. Received '$requestedArtifactFormat'."
}
if (requestedArtifactFormat == "cwasm" && wasmlineEngine != "cranelift") {
    error("Desktop CWASM requires -Pwasmline.engine=cranelift.")
}

val defaultCwasmTarget = when {
    System.getProperty("os.name").lowercase().contains("mac") &&
        System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64") -> "aarch64-macos"
    System.getProperty("os.name").lowercase().contains("mac") -> "x86_64-macos"
    System.getProperty("os.name").lowercase().contains("linux") &&
        System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64") -> "aarch64-linux"
    System.getProperty("os.name").lowercase().contains("linux") -> "x86_64-linux"
    System.getProperty("os.name").lowercase().contains("windows") -> "x86_64-windows"
    else -> error("Unsupported Wasmtime host: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
}
val requestedCwasmTarget = providers.gradleProperty("wasmline.compile.target")
    .orElse(defaultCwasmTarget)
    .get()
val samplePluginOutput = project(":sample-plugin").layout.buildDirectory.dir(
    "wasmline/output/crow.wasmline.demo-1.0.0",
)
val samplePluginArtifactName = when (requestedArtifactFormat) {
    "pwasm64" -> "demo-pulley64.pwasm"
    "cwasm" -> "demo-$requestedCwasmTarget.cwasm"
    else -> error("Unsupported wasmline artifact format: $requestedArtifactFormat")
}
val samplePluginArtifactExtension = samplePluginArtifactName.substringAfterLast('.')
val syncWasmlineSamplePlugin = tasks.register<Sync>("syncWasmlineSamplePlugin") {
    group = "wasmline"
    description = "Build and expose the selected Wasmline plugin artifact to desktop resources"
    dependsOn(project(":sample-plugin").tasks.named("wasmlineAssembleDebug"))
    from(samplePluginOutput) {
        include(samplePluginArtifactName)
        rename { "plugin.$samplePluginArtifactExtension" }
    }
    into(layout.buildDirectory.dir("generated/desktop-resources"))
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(syncWasmlineSamplePlugin)
    from(syncWasmlineSamplePlugin)
}

tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    dependsOn(syncWasmlineSamplePlugin)
    systemProperty("wasmline.artifact.format", if (requestedArtifactFormat == "pwasm64") "pwasm" else "cwasm")
}
