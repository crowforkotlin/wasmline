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
val wasmtimeVersion = providers.gradleProperty("wasmtime.version").orElse("47.0.2").get()
val configuredWasmtimeRoot = System.getenv("WASMTIME_ROOT")
val defaultCwasmTarget = when {
    System.getProperty("os.name").lowercase().contains("mac") &&
        System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64") -> "aarch64-macos"
    System.getProperty("os.name").lowercase().contains("mac") -> "x86_64-macos"
    System.getProperty("os.name").lowercase().contains("linux") &&
        System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64") -> "aarch64-linux"
    System.getProperty("os.name").lowercase().contains("linux") -> "x86_64-linux"
    System.getProperty("os.name").lowercase().contains("windows") -> "x86_64-windows"
    else -> error("Unsupported Wasmtime host: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
}
val cwasmTarget = providers.gradleProperty("wasmline.compile.target")
    .orElse(defaultCwasmTarget)
    .get()
val artifactFormat = providers.gradleProperty("wasmline.artifact.format").orNull?.lowercase()
val wasmtimeTargets = when (artifactFormat) {
    "pwasm32" -> listOf("pulley32")
    "pwasm64" -> listOf("pulley64")
    "cwasm" -> listOf(cwasmTarget)
    else -> listOf("pulley64", cwasmTarget)
}

wasmline {
    manifest {
        pluginId = "crow.wasmline.demo"
        version = "1.0.0"
        signingKey = file("../keys/private.key")
    }
    wasmtime {
        directory = file(configuredWasmtimeRoot ?: "$repoRoot/build/wasmline/wasmtime")
        autoDownload = true
        version = "v$wasmtimeVersion"
        targets = wasmtimeTargets
        githubToken = providers.gradleProperty("github.token").orNull
    }
    server {
        port = 8080
    }
    serverDeployVariant = "debug"
}
