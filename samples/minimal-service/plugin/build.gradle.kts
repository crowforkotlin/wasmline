@file:Suppress("OPT_IN_USAGE")

import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.gradle.WasmtimeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.wasmline)
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
            implementation(projects.commonService)
        }
    }
}

// Omit `-PaotGenerations` for all local generations, or use `latest`, `x.y.z`, or `FROM..THROUGH`.
val aotGenerations = providers.gradleProperty("aotGenerations").orNull

wasmline {
    manifest {
        pluginId = "crow.wasmline.minimal.service"
        version = rootProject.extra["sampleVersion"] as String
        privateKeyFile = rootProject.file("keys/private.key")
        executionModel = WasmlineExecutionModel.CORE_WASM
        invocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE
    }
    wasmtime {
        aotCompatibility {
            aotGenerations?.let { value ->
                val bounds = value.split("..")
                when (bounds.size) {
                    1 -> generations(value)
                    2 -> generations(from = bounds[0], through = bounds[1])
                    else -> error("Invalid -PaotGenerations=$value; use x.y.z, latest, or FROM..THROUGH.")
                }
            }
        }
        targets = listOf(WasmtimeTarget.PULLEY_64)
        autoDownload = true
    }
}
