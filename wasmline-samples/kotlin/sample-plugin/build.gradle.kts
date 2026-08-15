@file:Suppress("OPT_IN_USAGE")

import crow.wasmline.gradle.WasmtimeTarget


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
        directory = file(
            System.getenv("WASMTIME_ROOT") ?: "${rootDir.parentFile.parentFile}/build/wasmline/wasmtime",
        )
        autoDownload = true
        version = "v${providers.gradleProperty("wasmtime.version").orElse("47.0.2").get()}"
        githubToken = providers.gradleProperty("github.token").orNull
        targets = listOf(
        )
    }
    server {
        port = 8080
    }
    serverDeployVariant = "debug"
}
