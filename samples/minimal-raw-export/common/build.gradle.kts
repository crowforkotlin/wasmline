@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library.kmp)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.jetbrains.compose.compiler)
    alias(libs.plugins.wasmline)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(rootProject.extra["jbrVersion"] as Int)
    android {
        namespace = "crow.wasmline.samples.minimal.rawexport"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm("desktop")
    js { browser() }
    wasmJs { browser() }
    sourceSets {
        commonMain.dependencies {
            api(projects.minimalLibrary)
        }
    }
}

configurations.matching { it.name == "COMPOSE_SKIKO_JS_WASM_RUNTIME" }.configureEach {
    isCanBeConsumed = false
}

// This common module is not an executable, but Compose creates a desktopRun
// JavaExec task for its desktop target. Leave the sample root in charge of
// exposing the runnable desktop application task.
tasks.matching { it.name == "desktopRun" }.configureEach {
    enabled = false
}
