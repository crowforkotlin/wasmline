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
        pluginId = "crow.wasmline.sample.component-export"
        version = "1.0.0"
        signingKey = file("../keys/private.key")
        executionModel = WasmlineExecutionModel.COMPONENT_MODEL
        invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT
        exportName = "wasmline:sample-component-export/calculator@1.0.0#add"
        contractMetadata = mapOf(
            "params" to "s32,s32",
            "result" to "s32",
        )
    }
    wasmtime {
        autoDownload = true
    }
    component {
        world = "plugin"
        kotlinImports = "bindings.*"
    }
}
