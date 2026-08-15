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
    }
    component {
        componentInput.set(layout.projectDirectory.file("input/plugin.component.wasm"))
        codec = "protobuf"
        serviceProtocolVersion = "1"
    }
}
