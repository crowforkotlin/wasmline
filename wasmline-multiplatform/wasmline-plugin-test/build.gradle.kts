@file:Suppress("OPT_IN_USAGE")

import org.gradle.api.tasks.testing.Test

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
    jvm()
    wasmWasi {
        nodejs()
        binaries.library()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.crow.wasmline)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.protobuf)
            implementation(libs.kotlin.stdlib)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.crow.wasmline.loader)
            implementation(libs.crow.wasmline.engine.pulley)
            implementation(libs.crow.wasmline.engine.cranelift)
        }
    }
}

val testPluginId = "crow.wasmline.test.plugin"
val testPluginVersion = "1.0.0"
val testPluginCompileTarget = when {
    System.getProperty("os.name").lowercase().contains("mac") &&
        System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64") -> "aarch64-macos"

    System.getProperty("os.name").lowercase().contains("mac") -> "x86_64-macos"

    System.getProperty("os.name").lowercase().contains("linux") &&
        System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64") -> "aarch64-linux"

    System.getProperty("os.name").lowercase().contains("linux") -> "x86_64-linux"

    else -> error("Unsupported Wasmtime test host: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
}
val testPluginArtifactDirectory = layout.buildDirectory.dir("wasmline/output/$testPluginId-$testPluginVersion")
val testPluginArtifact = testPluginArtifactDirectory.map { it.file("plugin-$testPluginCompileTarget.cwasm") }

tasks.named<Test>("jvmTest") {
    dependsOn("wasmlineAssembleDebug")
    systemProperty("wasmline.plugin.artifact.path", testPluginArtifact.get().asFile.absolutePath)
}

// WASMTIME directory uses a path relative to the multiplatform root.
wasmline {
    val wasmtimeVersion = "47.0.2"
    val wasmtimePlatformDir = "wasmtime-v$wasmtimeVersion-$testPluginCompileTarget-min"
    manifest {
        pluginId = testPluginId
        version = testPluginVersion
        signingKey = file("keys/private.key")
    }
    wasmtime {
        directory = file("${file("build/wasmline/wasmtime")}/$wasmtimePlatformDir")
        autoDownload = true
        version = "v$wasmtimeVersion"
        targets = listOf(testPluginCompileTarget)
        githubToken = providers.gradleProperty("github.token").orNull
    }
    server {
        port = 8090
    }
    serverDeployVariant = "debug"
}
