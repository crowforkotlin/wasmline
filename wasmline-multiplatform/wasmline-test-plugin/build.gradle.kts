@file:Suppress("OPT_IN_USAGE")

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
            implementation(libs.crow.wasmline)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.protobuf)
            implementation(libs.kotlin.stdlib)
        }
    }
}

// WASMTIME directory uses relative path from multiplatform root (same pattern as run-sample-common.sh)
val wasmtimeRoot = file("build/wasmline/wasmtime")
val wasmtimeVersion = "47.0.2"
val wasmtimePlatformDir = "wasmtime-v${wasmtimeVersion}-x86_64-linux-min"

wasmline {
    manifest {
        pluginId = "crow.wasmline.test.plugin"
        version = "1.0.0"
        signingKey = file("keys/private.key")
    }
    wasmtime {
        // Locate wasmtime executable: {multiplatform_root}/build/wasmline/wasmtime/{VERSION}-{platform}-min/
        // This matches the shared location pattern used by run-sample-common.sh and all sample builds
        directory = file("$wasmtimeRoot/$wasmtimePlatformDir")

        version = "v$wasmtimeVersion"

        // Only test Linux target for this module
        targets = listOf("x86_64-linux")
    }
    server {
        port = 8090
    }
    serverDeployVariant = "debug"
}

// Add dependency to ensure wasmtime is checked before assembling
// Use afterEvaluate to ensure tasks are registered by WasmlinePlugin first
afterEvaluate {
    val wasmlineAssembleDebugTask = project.tasks.findByName("wasmlineAssembleDebug")
    if (wasmlineAssembleDebugTask != null) {
        wasmlineAssembleDebugTask.dependsOn("checkWasmlineToolchain")
    } else {
        logger.warn("Warning: wasmlineAssembleDebug task not found. Make sure the 'wasmline' plugin is applied.")
    }
}
