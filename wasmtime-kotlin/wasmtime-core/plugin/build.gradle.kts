@file:Suppress("OPT_IN_USAGE")

import org.jetbrains.kotlin.gradle.idea.tcs.extras.isCommonizedKey
import org.jetbrains.kotlin.gradle.internal.platform.wasm.WasmPlatforms.wasmJs
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
        nodejs()
        binaries.executable()
    }
    sourceSets {
        wasmWasiMain.dependencies {
            implementation(projects.wasmtimeCore.core)
            implementation("com.squareup.okio:okio-wasm-wasi:3.16.4")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-wasm-wasi:1.9.0")
            implementation("org.jetbrains.kotlinx:atomicfu-wasm-wasi:0.30.0-beta")
            implementation("org.jetbrains.kotlin:kotlin-stdlib-wasm-wasi:2.3.0-RC")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-wasm-wasi:1.10.2")
        }
    }
}

tasks.register<Copy>("wasmCopy") {
    val assetsDir = rootProject.file("./${projects.wasmtimeApp.android.path.replace(":","/")}/src/androidMain/assets")
    from(file("build/compileSync/wasmWasi/main/productionExecutable/optimized"))
    include("*.wasm")
    rename { "plugin.wasm" }
    into(assetsDir)
}
tasks.named("compileProductionExecutableKotlinWasmWasiOptimize") { finalizedBy("wasmCopy") }