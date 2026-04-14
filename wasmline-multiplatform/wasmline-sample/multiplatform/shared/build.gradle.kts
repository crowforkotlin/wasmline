@file:Suppress("unused", "UnstableApiUsage")

import org.jetbrains.kotlin.konan.target.HostManager


plugins {
    alias(libs.plugins.app.base.multiplatform.library)
    alias(libs.plugins.jetbrains.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.serialization)
    // alias(libs.plugins.wasmline)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.wasmline)
                api(projects.wasmlineSample.common)
                api(compose.material3)
                api(compose.components.resources)
                api(compose.runtime)
                api(compose.foundation)
                api(compose.material)
                api(compose.ui)
                api(libs.jetbrains.compose.components.resources)
                api(libs.jetbrains.lifecycle.viewmodel)
                api(libs.jetbrains.lifecycle.runtime.compose)
                api(libs.jetbrains.compose.material.window)
                api(libs.jetbrains.compose.material.icons.core)
                api(libs.coil)

                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.serialization.protobuf)
            }
        }
        val androidMain by getting {
            dependencies {
                api(libs.androidx.activity.compose)
            }
        }
        val desktopMain by getting {
            dependencies {
                api(compose.desktop.currentOs)
                api(libs.jetbrains.jewel.decorated)
                api(libs.conveyor)
            }
        }
        if (HostManager.hostIsMac) {
            val iosSimulatorArm64Main by getting { }
        }
    }
}