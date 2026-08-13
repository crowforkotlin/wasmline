@file:Suppress("OPT_IN_USAGE")

import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol

plugins {
    alias(libs.plugins.kotlin.multiplatform)
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
            implementation(libs.kotlin.stdlib)
        }
    }
}

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
val cwasmTarget = providers.gradleProperty("wasmline.compile.target").orElse(defaultCwasmTarget).get()
val artifactFormat = providers.gradleProperty("wasmline.artifact.format").orNull?.lowercase()
val wasmtimeTargets = when (artifactFormat) {
    "pwasm64", "pwasm" -> listOf("pulley64")
    "cwasm" -> listOf(cwasmTarget)
    else -> listOf("pulley64", cwasmTarget)
}

wasmline {
    manifest {
        pluginId = "crow.wasmline.sample.raw-export"
        version = "1.0.0"
        signingKey = file("../keys/private.key")
        executionModel = WasmlineExecutionModel.CORE_WASM
        invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT
        exportName = "add_i32"
        contractMetadata = mapOf(
            "params" to "s32,s32",
            "result" to "s32",
        )
    }
    wasmtime {
        directory = file(configuredWasmtimeRoot ?: "$repoRoot/build/wasmline/wasmtime")
        autoDownload = true
        version = "v$wasmtimeVersion"
        targets = wasmtimeTargets
    }
}
