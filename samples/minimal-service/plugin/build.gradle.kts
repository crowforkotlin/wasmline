@file:Suppress("OPT_IN_USAGE")

import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.gradle.WasmtimeTarget
import crow.wasmline.plugin.core.aot.AotGenerations

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

// Omit `-PaotGenerations` for the current generation, or use current, latest, earliest, all, x.y.z, or MIN..MAX.
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
                when {
                    bounds.size == 1 -> when (value) {
                        "current" -> generations(AotGenerations.Current)
                        "latest" -> generations(AotGenerations.Latest)
                        "earliest" -> generations(AotGenerations.Earliest)
                        "all" -> generations(AotGenerations.All)
                        else -> generations(value)
                    }
                    bounds.size == 2 -> {
                        val (min, max) = bounds
                        require(min.isNotEmpty() && max.isNotEmpty()) {
                            "Invalid -PaotGenerations=$value; a range needs two concrete x.y.z endpoints."
                        }
                        generations(min = min, max = max)
                    }
                    else -> error(
                        "Invalid -PaotGenerations=$value; use current, latest, earliest, all, " +
                            "a concrete x.y.z, or a concrete MIN..MAX interval.",
                    )
                }
            }
        }
        targets = listOf(WasmtimeTarget.PULLEY_64)
        autoDownload = true
    }
}
