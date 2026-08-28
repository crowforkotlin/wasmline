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

val samplePackages = listOf(
    Triple(
        project(":sample-plugin"),
        "wasmline/output/crow.wasmline.demo-1.0.0",
        "wasmline-packages/core-service",
    ),
    Triple(
        project(":sample-raw-export-plugin"),
        "wasmline/output/crow.wasmline.sample.raw-export-1.0.0",
        "wasmline-packages/raw-export",
    ),
    Triple(
        project(":sample-component-plugin"),
        "wasmline/output/crow.wasmline.component.sample-1.0.0",
        "wasmline-packages/component-service",
    ),
    Triple(
        project(":sample-component-export-plugin"),
        "wasmline/output/crow.wasmline.sample.component-export-1.0.0",
        "wasmline-packages/component-export",
    ),
)
val syncWasmlineSamplePackages = tasks.register<Sync>("syncWasmlineSamplePackages") {
    group = "wasmline"
    description = "Build and expose all four signed Wasmline sample packages to Desktop resources"
    samplePackages.forEach { (sampleProject, outputPath, resourcePath) ->
        dependsOn(sampleProject.tasks.named("wasmlineAssembleDebug"))
        from(sampleProject.layout.buildDirectory.dir(outputPath)) {
            include("manifest.wlm", "artifacts/**")
            into(resourcePath)
        }
    }
    into(layout.buildDirectory.dir("generated/desktop-resources"))
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(syncWasmlineSamplePackages)
    from(syncWasmlineSamplePackages)
    from(layout.projectDirectory.file("appIcons/LinuxIcon.png")) {
        rename { "wasmline-icon.png" }
    }
}

val jbrLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(21)
    vendor.set(JvmVendorSpec.JETBRAINS)
}
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    dependsOn(syncWasmlineSamplePackages)
    javaLauncher.set(jbrLauncher)
}

tasks.register<JavaExec>("verifyWasmlineSamples") {
    group = "verification"
    description = "Load and invoke all four bundled Wasmline sample contracts without opening the UI"
    dependsOn(tasks.named("classes"), syncWasmlineSamplePackages)
    mainClass.set("crow.wasmline.sample.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(jbrLauncher)
    systemProperty("wasmline.sample.smoke", "true")
}
