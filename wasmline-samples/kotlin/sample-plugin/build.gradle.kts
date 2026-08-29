@file:Suppress("OPT_IN_USAGE")

import crow.wasmline.gradle.WasmlineBuildVariant

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

wasmline {
    manifest {
        pluginId = "crow.wasmline.demo"
        version = "1.0.0"
        signingKey = file("../keys/private.key")
    }
    wasmtime {
        aotCompatibility {
            current()
        }
        autoDownload = true
        githubToken = providers.gradleProperty("github.token").orNull
        targets = emptyList()
    }
    server {
        port = 8080
        deployVariant = WasmlineBuildVariant.DEBUG
    }
}
