@file:Suppress("UnstableApiUsage")

import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val wasmlineEngine = providers.gradleProperty("wasmline.engine")
    .map { it.lowercase() }
    .orElse("pulley")
    .get()
require(wasmlineEngine in setOf("pulley", "cranelift")) {
    "Unsupported wasmline.engine '$wasmlineEngine'. Expected pulley or cranelift."
}

val hostTarget = when (HostManager.host) {
    KonanTarget.LINUX_X64 -> kotlin.linuxX64()
    KonanTarget.LINUX_ARM64 -> kotlin.linuxArm64()
    KonanTarget.MACOS_ARM64 -> kotlin.macosArm64()
    KonanTarget.MINGW_X64 -> kotlin.mingwX64()
    else -> error(
        "The Kotlin/Native sample supports Linux x64/ARM64, macOS ARM64, and Windows x64. " +
            "Current host: ${HostManager.hostName}.",
    )
}

kotlin {
    hostTarget.binaries.executable {
        baseName = "wasmline-native-sample"
        entryPoint = "crow.wasmline.sample.native.main"
        if (HostManager.host == KonanTarget.LINUX_X64 || HostManager.host == KonanTarget.LINUX_ARM64) {
            linkerOpts("-Wl,--as-needed")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.crow.wasmline.loader)
            implementation(libs.kotlinx.coroutines)
            if (wasmlineEngine == "cranelift") {
                implementation(libs.crow.wasmline.engine.cranelift)
            } else {
                implementation(libs.crow.wasmline.engine.pulley)
            }
        }
    }
}

val rawExportPackage = project(":sample-raw-export-plugin").layout.buildDirectory.dir(
    "wasmline/output/crow.wasmline.sample.raw-export-1.0.0",
)
val rawExportManifest = rawExportPackage.map { it.file("manifest.wlm") }
val debugExecutable = hostTarget.binaries.getExecutable(NativeBuildType.DEBUG)

val runKotlinNativeSample = tasks.register<Exec>("runKotlinNativeSample") {
    group = "application"
    description = "Build and run the Kotlin/Native Wasmline smoke sample on the current host"
    dependsOn(
        project(":sample-raw-export-plugin").tasks.named("wasmlineAssembleDebug"),
        debugExecutable.linkTaskProvider,
    )
    inputs.dir(rawExportPackage)
    executable(debugExecutable.outputFile.absolutePath)
    args(
        rawExportManifest.get().asFile.absolutePath,
        "19",
        "23",
        wasmlineEngine,
    )
}

tasks.register("verifyKotlinNativeSample") {
    group = "verification"
    description = "Verify signed package loading and Raw Export invocation on Kotlin/Native"
    dependsOn(runKotlinNativeSample)
}
