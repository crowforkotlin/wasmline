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
wasmline {
    val wasmtimeVersion = "47.0.2"
    val wasmtimePlatformDir = "wasmtime-v${wasmtimeVersion}-x86_64-linux-min"
    manifest {
        pluginId = "crow.wasmline.test.plugin"
        version = "1.0.0"
        signingKey = file("keys/private.key")
    }
    wasmtime {
        directory = file("${file("build/wasmline/wasmtime")}/$wasmtimePlatformDir")
        autoDownload = true
        version = "v$wasmtimeVersion"
        targets = listOf("x86_64-linux")
        githubToken = providers.gradleProperty("github.token").orNull
    }
    server {
        port = 8090
    }
    serverDeployVariant = "debug"
}
