@file:Suppress("unused", "UnstableApiUsage")
@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.AbstractCopyTask


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.wasmline)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
}

kotlin {
    wasmJs {
        browser()
        binaries.executable()
    }
    js {
        browser()
        binaries.executable()
    }
    sourceSets {
        commonMain.dependencies {
            implementation(projects.sampleApps.multiplatform.shared)
            implementation(projects.sampleCommon)
            implementation(libs.crow.wasmline)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines)
        }
    }
}

val samplePluginOutput = project(":sample-plugin").layout.buildDirectory.dir(
    "wasmline/output/crow.wasmline.demo-1.0.0",
)
val syncWasmlineSamplePlugin = tasks.register<Sync>("syncWasmlineSamplePlugin") {
    group = "wasmline"
    description = "Build and expose the signed Wasmline plugin package to web resources"
    dependsOn(project(":sample-plugin").tasks.named("wasmlineAssembleDebug"))
    from(samplePluginOutput) {
        include("manifest.wlm", "artifacts/**")
        into("plugin")
    }
    into(layout.buildDirectory.dir("generated/web-resources"))
}

tasks.withType<AbstractCopyTask>().matching {
    it.name in setOf(
        "jsProcessResources",
        "wasmJsProcessResources",
    )
}.configureEach {
    dependsOn(syncWasmlineSamplePlugin)
    from(syncWasmlineSamplePlugin)
}

tasks.matching {
    it.name in setOf(
        "jsBrowserDevelopmentRun",
        "wasmJsBrowserDevelopmentRun",
    )
}.configureEach {
    dependsOn(syncWasmlineSamplePlugin)
}
