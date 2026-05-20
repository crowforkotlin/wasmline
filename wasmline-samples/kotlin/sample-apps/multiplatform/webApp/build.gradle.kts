@file:Suppress("unused", "UnstableApiUsage")
@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.konan.target.HostManager


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.wasmline)
}

val pluginWasmResourceRoot = layout.buildDirectory.dir("generated/pluginWasm/webMain")

val syncPluginWasmForWeb by tasks.registering(Copy::class) {
    val pluginProject = project(":sample-plugin")
    dependsOn(pluginProject.tasks.named("compileProductionLibraryKotlinWasmWasi"))
    from(pluginProject.layout.buildDirectory.dir("compileSync/wasmWasi/main/productionLibrary/kotlin")) {
        include("wasmline-sample-sample-plugin.wasm")
    }
    into(pluginWasmResourceRoot.map { it.dir("plugin") })
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
            implementation(libs.kotlinx.coroutines)
        }
    }
}

tasks.matching { it.name == "wasmJsProcessResources" || it.name == "jsProcessResources" }.configureEach {
    dependsOn(syncPluginWasmForWeb)
    if (this is Copy) {
        from(pluginWasmResourceRoot)
    }
}
