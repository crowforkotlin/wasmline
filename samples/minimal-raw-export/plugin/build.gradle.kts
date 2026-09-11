@file:Suppress("OPT_IN_USAGE")

import crow.wasmline.RawAbiMetadata
import crow.wasmline.RawExport
import crow.wasmline.RawExportKind
import crow.wasmline.RawFunctionSignature
import crow.wasmline.RawValueType
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.gradle.WasmtimeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.wasmline)
}

kotlin {
    jvmToolchain(rootProject.extra["jbrVersion"] as Int)
    wasmWasi {
        nodejs()
        binaries.library()
    }
}

wasmline {
    manifest {
        pluginId = "crow.wasmline.minimal.raw-export"
        version = rootProject.extra["sampleVersion"] as String
        signingKey = rootProject.file("keys/private.key")
        executionModel = WasmlineExecutionModel.CORE_WASM
        invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT
        exportName = "add_i32"
        rawAbi = RawAbiMetadata(
            exports = listOf(
                RawExport(
                    name = "add_i32",
                    kind = RawExportKind.FUNCTION,
                    signature = RawFunctionSignature(
                        parameters = listOf(RawValueType.I32, RawValueType.I32),
                        results = listOf(RawValueType.I32),
                    ),
                ),
            ),
        )
    }
    wasmtime {
        aotCompatibility { current() }
        targets = listOf(WasmtimeTarget.PULLEY_64)
        autoDownload = true
    }
}
