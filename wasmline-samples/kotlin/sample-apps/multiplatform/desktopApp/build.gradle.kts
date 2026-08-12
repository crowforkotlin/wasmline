import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.jetbrains.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
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

val samplePluginOutput = project(":sample-plugin").layout.buildDirectory.dir(
    "wasmline/output/crow.wasmline.demo-1.0.0",
)
val syncWasmlineSamplePlugin = tasks.register<Sync>("syncWasmlineSamplePlugin") {
    group = "wasmline"
    description = "Build and expose the signed Wasmline plugin package to desktop resources"
    dependsOn(project(":sample-plugin").tasks.named("wasmlineAssembleDebug"))
    from(samplePluginOutput) {
        include("manifest.wlm", "*.wasm", "*.pwasm", "*.cwasm")
        into("wasmline-package")
    }
    into(layout.buildDirectory.dir("generated/desktop-resources"))
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(syncWasmlineSamplePlugin)
    from(syncWasmlineSamplePlugin)
    from(layout.projectDirectory.file("appIcons/LinuxIcon.png")) {
        rename { "wasmline-icon.png" }
    }
}

val jbrLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(21)
    vendor.set(JvmVendorSpec.JETBRAINS)
}
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    dependsOn(syncWasmlineSamplePlugin)
    javaLauncher.set(jbrLauncher)
}
