@file:Suppress("OPT_IN_USAGE")

import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol

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
            implementation(libs.kotlin.stdlib)
            implementation(libs.kotlinx.serialization.protobuf)
        }
    }
}

wasmline {
    manifest {
        pluginId = "crow.wasmline.component.sample"
        version = "1.0.0"
        signingKey = file("../keys/private.key")
        executionModel = WasmlineExecutionModel.COMPONENT_MODEL
        invocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE
        exportName = "plugin/invoke"
    }
    wasmtime {
        autoDownload = true
    }
    component {
        codec = "protobuf"
        serviceProtocolVersion = "1"
    }
}
