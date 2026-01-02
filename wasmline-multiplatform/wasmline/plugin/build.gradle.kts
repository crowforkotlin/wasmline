@file:Suppress("OPT_IN_USAGE")


import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import kotlin.text.replace

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

kotlin {

    wasmWasi {
        binaries.library()
    }

    sourceSets {
        wasmWasiMain.dependencies {
//            implementation(projects.wasmlineCore.core)
//            implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.0-RC")
//            implementation("com.squareup.okio:okio-wasm-wasi:3.16.4")
//            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-wasm-wasi:1.9.0")
//            implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf-wasm-wasi:1.9.0")
//            implementation("org.jetbrains.kotlinx:atomicfu-wasm-wasi:0.30.0-beta")
//            implementation("org.jetbrains.kotlin:kotlin-stdlib-wasm-wasi:2.3.0-RC")
//            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-wasm-wasi:1.10.2")
        }
    }
}

tasks.register<Copy>("wasmCopy") {
    val assetsDir = rootProject.file("./${projects.wasmlineSample.android.path.replace(":","/")}/src/androidMain/assets")
    from(file("build/compileSync/wasmWasi/main/productionExecutable/optimized"))
    from(file("build/compileSync/wasmWasi/main/productionLibrary/optimized"))
    include("*.wasm")
    rename { "plugin.wasm" }
    into(assetsDir)
}
tasks.findByName("compileProductionExecutableKotlinWasmWasiOptimize")?.finalizedBy("wasmCopy")
tasks.findByName("compileProductionLibraryKotlinWasmWasiOptimize")?.finalizedBy("wasmCopy")