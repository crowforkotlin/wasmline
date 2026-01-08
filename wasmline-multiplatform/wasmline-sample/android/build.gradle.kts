@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    id("app.base.android")
}

androidApplication {
    config(
        versionCode = 1,
        versionName = "1.0.0-release",
    ) {

    }
}

dependencies {
    api(libs.androidx.core.ktx)
    api(libs.androidx.activity.ktx)
    api(libs.androidx.material)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.serialization.protobuf)
    api(projects.wasmline.core)
}

afterEvaluate {
    tasks.named<com.android.build.gradle.tasks.MergeSourceSetFolders>("mergeDebugAssets") {
        dependsOn(rootProject.tasks.findByPath("wasmline-sample:plugin:copyWasmArtifacts"))
    }
    afterEvaluate {
        tasks.findByName("assembleDebug")?.apply {
            rootProject.tasks.findByPath("wasmline-sample:plugin:compileProductionExecutableKotlinWasmWasiOptimize")
                ?.also { task -> dependsOn(task) }
            rootProject.tasks.findByPath("wasmline-sample:plugin:compileProductionLibraryKotlinWasmWasiOptimize")
                ?.also { task -> dependsOn(task) }
        }
    }
}
