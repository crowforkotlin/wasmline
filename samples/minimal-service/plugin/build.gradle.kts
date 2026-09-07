@file:Suppress("OPT_IN_USAGE")

import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.gradle.WasmtimeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("crow.wasmline")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(rootProject.extra["jbrVersion"] as Int)
    wasmWasi {
        nodejs()
        binaries.library()
    }
    sourceSets {
        wasmWasiMain.dependencies {
            implementation(project(":contract"))
        }
    }
}

wasmline {
    manifest {
        pluginId = "crow.wasmline.minimal.service"
        version = rootProject.extra["sampleVersion"] as String
        signingKey = rootProject.file("keys/private.key")
        executionModel = WasmlineExecutionModel.CORE_WASM
        invocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE
    }
    wasmtime {
        aotCompatibility { current() }
        targets = listOf(WasmtimeTarget.PULLEY_64)
        autoDownload = true
    }
}
