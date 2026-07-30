@file:Suppress("OPT_IN_USAGE")

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.wasmline)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {

    wasmWasi {
        nodejs()
        binaries.library()
    }

    sourceSets {
        wasmWasiMain.dependencies {
            implementation(projects.sampleCommon)
            implementation(libs.crow.wasmline)
            implementation(libs.okio.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.protobuf)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlin.stdlib)
            implementation(libs.kotlinx.coroutines)
        }
    }
}

// Repo root: wasmline-samples/kotlin -> wasmline-samples -> wasmline
val repoRoot = rootDir.parentFile.parentFile

wasmline {
    manifest {
        pluginId = "crow.wasmline.demo"
        version = "1.0.0"
        signingKey = file("../keys/private.key")
    }
    wasmtime {
        // wasmtime-min is downloaded by the CLI `download` command into
        // {repoRoot}/build/wasmline/wasmtime/wasmtime-v{VERSION}-{platform}/
        directory = file("$repoRoot/build/wasmline/wasmtime/wasmtime-v47.0.2-x86_64-linux-min")
    }
    server {
        port = 8080
    }
    serverDeployVariant = "debug"
}
