@file:Suppress("unused")

plugins {
    id("app.base.application")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
}


java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}


composeApplication {
    config(
        versionCode = 1,
        versionName = "1.0.0",
        desktopMainClass = "MainKt"
    )
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.wasmline.core)
                api(projects.wasmlineSample.common)
                implementation(compose.material3)
                implementation(compose.components.resources)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material)
                implementation(compose.ui)
                implementation(libs.jetbrains.compose.components.resources)
                implementation(libs.jetbrains.lifecycle.viewmodel)
                implementation(libs.jetbrains.lifecycle.runtime.compose)
                implementation(libs.jetbrains.compose.material.window)
                implementation(libs.jetbrains.compose.material.icons.core)
                implementation(libs.coil)

                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.serialization.protobuf)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.activity.compose)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.jetbrains.jewel.decorated)
                implementation(libs.conveyor)
            }
        }
        val iosSimulatorArm64Main by getting { }
    }
}

compose.desktop { }


afterEvaluate {

    rootProject.tasks.findByPath("wasmline-sample:multiplatform:mergeDebugAssets")
        ?.dependsOn(rootProject.tasks.findByPath("wasmline-sample:plugin:copyWasmArtifacts"))
    tasks.findByName("assembleDebug")?.apply {
        rootProject.tasks.findByPath("wasmline-sample:plugin:compileProductionExecutableKotlinWasmWasiOptimize")
            ?.also { task -> dependsOn(task) }
        rootProject.tasks.findByPath("wasmline-sample:plugin:compileProductionLibraryKotlinWasmWasiOptimize")
            ?.also { task -> dependsOn(task) }
    }


    tasks.findByName("desktopRun")?.apply {
        rootProject.tasks.findByPath("wasmline-sample:plugin:compileProductionExecutableKotlinWasmWasiOptimize")
            ?.also { task -> dependsOn(task) }
        rootProject.tasks.findByPath("wasmline-sample:plugin:compileProductionLibraryKotlinWasmWasiOptimize")
            ?.also { task -> dependsOn(task) }
    }
}
