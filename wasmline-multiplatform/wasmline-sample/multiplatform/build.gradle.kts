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
        desktopMainClass = "MainKt",
        jsModuleName = Config.ApplicationName,
        jsOutputFileName = "${Config.ApplicationName}.js",
    )
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
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
                implementation(projects.wasmlineSample.common)
                implementation(projects.wasmline.core)
                implementation(libs.androidx.activity.compose)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(projects.wasmlineSample.common)
                implementation(projects.wasmline.core)
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.serialization.protobuf)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.jetbrains.jewel.decorated)
                implementation(libs.conveyor)
            }
        }
        val iosSimulatorArm64Main by getting {
            dependencies {
                implementation(projects.wasmline.core)
            }
        }
    }
    val configureCInterop = { target: org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget ->
        target.compilations.getByName("main") {
            val myclib by cinterops.creating {
                // 指定 def 文件
                defFile(project.file("src/nativeInterop/cinterop/myclib.def"))
                // 指定头文件和源码所在的目录
                includeDirs(project.file("src/nativeInterop/c"))
            }
        }
        // 【注意】 把之前那个 target.binaries.all { linkerOpts(...) } 删掉
        // 我们改用 def 文件里的 #include "myclib.c" 来解决编译问题
    }

    iosArm64 { configureCInterop(this) }
    iosSimulatorArm64 { configureCInterop(this) }
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
