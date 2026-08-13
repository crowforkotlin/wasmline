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

// The Wasm target registers the Wasmline task graph; componentInput bypasses its compilation.
kotlin {
    wasmWasi {
        nodejs()
        binaries.library()
    }
}

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
        pluginId = "crow.wasmline.component.fixture"
        version = "1.0.0"
        signingKey = file("../keys/private.key")
        executionModel = WasmlineExecutionModel.COMPONENT_MODEL
        invocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE
        exportName = "plugin/invoke"
    }
    wasmtime {
        autoDownload = true
        targets = wasmtimeTargets
    }
    component {
        componentInput.set(layout.projectDirectory.file("input/plugin.component.wasm"))
        codec = "protobuf"
        serviceProtocolVersion = "1"
    }
}
