@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library.kmp)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.jetbrains.compose.compiler)
}

// Both examples include these sources, but must not share generated output.
layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("minimal-support"))

kotlin {
    jvmToolchain(rootProject.extra["jbrVersion"] as Int)
    android {
        namespace = "crow.wasmline.samples.minimal"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm("desktop")
    js { browser() }
    wasmJs { browser() }
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api(libs.jetbrains.compose.runtime)
            api(libs.jetbrains.compose.foundation)
            api(libs.jetbrains.compose.material)
            api(libs.crow.wasmline.loader)
            api(libs.kotlinx.coroutines)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.crow.wasmline.engine.pulley)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.crow.wasmline.engine.pulley)
                implementation(libs.slf4j.nop)
            }
        }
        webMain.dependencies {
            implementation(libs.crow.wasmline.network.ktor)
            implementation(libs.ktor.client.js)
        }
    }
}

configurations.matching { it.name == "COMPOSE_SKIKO_JS_WASM_RUNTIME" }.configureEach {
    isCanBeConsumed = false
}
