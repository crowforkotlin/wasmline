@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library.kmp)
    alias(libs.plugins.kotlin.serialization)
    id("crow.wasmline")
}

kotlin {
    jvmToolchain(rootProject.extra["jbrVersion"] as Int)
    jvm()
    android {
        namespace = "crow.wasmline.minimal.service.contract"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    js { browser() }
    wasmJs { browser() }
    wasmWasi { nodejs() }
    sourceSets {
        commonMain.dependencies { api(libs.crow.wasmline) }
    }
}
