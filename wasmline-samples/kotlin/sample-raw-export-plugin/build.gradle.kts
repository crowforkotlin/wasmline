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
        directory = file(
            System.getenv("WASMTIME_ROOT") ?: "${rootDir.parentFile.parentFile}/build/wasmline/wasmtime",
        )
        autoDownload = true
        version = "v${providers.gradleProperty("wasmtime.version").orElse("48.0.1").get()}"
    }
}
