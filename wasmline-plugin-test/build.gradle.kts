@file:Suppress("OPT_IN_USAGE")

import crow.wasmline.gradle.WasmlineBuildVariant
import crow.wasmline.gradle.WasmtimeTarget
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
            implementation(libs.kotlinx.coroutines)
            implementation(libs.crow.wasmline.loader)
            implementation(libs.crow.wasmline.engine.cranelift)
        }
    }
}

val testPluginId = "crow.wasmline.test.plugin"
val testPluginVersion = "1.0.0"
val testPluginCompileTarget = WasmtimeTarget.currentHost
val testPluginArtifactDirectory = layout.buildDirectory.dir("wasmline/output/$testPluginId-$testPluginVersion")
val testPluginManifest = testPluginArtifactDirectory.map { it.file("manifest.wlm") }

tasks.named<Test>("jvmTest") {
    dependsOn("wasmlineAssembleDebug")
    systemProperty("wasmline.plugin.manifest.path", testPluginManifest.get().asFile.absolutePath)
}

wasmline {
    manifest {
        pluginId = testPluginId
        version = testPluginVersion
        signingKey = file("keys/private.key")
    }
    wasmtime {
        aotCompatibility {
            current()
        }
        autoDownload = true
        targets = listOf(testPluginCompileTarget)
        githubToken = providers.gradleProperty("github.token").orNull
    }
    server {
        port = 8090
        deployVariant = WasmlineBuildVariant.DEBUG
    }
}
