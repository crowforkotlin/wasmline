@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library.kmp)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.jetbrains.compose.compiler)
    id("crow.wasmline")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(rootProject.extra["jbrVersion"] as Int)
    android {
        namespace = "crow.wasmline.minimal.rawexport.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm("desktop")
    js { browser() }
    wasmJs { browser() }
    sourceSets {
        commonMain.dependencies {
            api(project(":minimal-support"))
        }
    }
}

configurations.matching { it.name == "COMPOSE_SKIKO_JS_WASM_RUNTIME" }.configureEach {
    isCanBeConsumed = false
}
