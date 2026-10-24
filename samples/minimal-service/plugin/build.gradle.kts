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

// `-PaotSelector=current|minimum|all|ranges` and `-PaotMinSdk=x.y.z` exercise
// every compatibility strategy against the same guest module.
val aotSelector = providers.gradleProperty("aotSelector").getOrElse("current")
val aotMinSdk = providers.gradleProperty("aotMinSdk").getOrElse("1.0.0")
val aotFrom = providers.gradleProperty("aotFrom").getOrElse("1.0.0")
val aotThrough = providers.gradleProperty("aotThrough").getOrElse("1.0.1")

wasmline {
    manifest {
        pluginId = "crow.wasmline.minimal.service"
        version = rootProject.extra["sampleVersion"] as String
        privateKeyFile = rootProject.file("keys/private.key")
        executionModel = WasmlineExecutionModel.CORE_WASM
        invocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE
        minSdkVersion = aotMinSdk
    }
    wasmtime {
        aotCompatibility {
            when (aotSelector) {
                "current" -> current()
                "minimum" -> minimum()
                "all" -> all()
                "ranges" -> versionRanges { include(from = aotFrom, through = aotThrough) }
                else -> error("Unknown -PaotSelector=$aotSelector; use current, minimum, all, or ranges.")
            }
        }
        targets = listOf(WasmtimeTarget.PULLEY_64)
        autoDownload = true
    }
}
