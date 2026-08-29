@file:Suppress("OPT_IN_USAGE")

import crow.wasmline.RawAbiMetadata
import crow.wasmline.RawExport
import crow.wasmline.RawExportKind
import crow.wasmline.RawFunctionSignature
import crow.wasmline.RawValueType
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
        aotCompatibility {
            current()
        }
        autoDownload = true
    }
}
