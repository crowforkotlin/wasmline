@file:Suppress("unused", "UnstableApiUsage")

import org.jetbrains.kotlin.konan.target.HostManager


plugins {
    alias(libs.plugins.app.base.multiplatform.library)
    alias(libs.plugins.jetbrains.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.wasmline)
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
                api(libs.crow.wasmline)
                api(libs.crow.wasmline.loader)

                api(projects.sampleCommon)

                api(libs.jetbrains.compose.runtime)
                api(libs.jetbrains.compose.ui)
                api(libs.jetbrains.compose.foundation)
                api(libs.jetbrains.compose.material)
                api(libs.jetbrains.compose.material3)
                api(libs.jetbrains.compose.components.resources)

                api(libs.jetbrains.compose.material.icons.core)
                api(libs.jetbrains.compose.material.window)



                api(libs.jetbrains.lifecycle.viewmodel)
                api(libs.jetbrains.lifecycle.runtime.compose)
                api(libs.coil)

                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.serialization.protobuf)
            }
        }
        val androidMain by getting {
            dependencies {
                api(libs.androidx.activity.compose)
                api(libs.ktor.client.okhttp)
            }
        }
        val desktopMain by getting {
            dependencies {
                api(compose.desktop.currentOs)
                api(libs.jetbrains.jewel.decorated)
                api(libs.conveyor)
                api(libs.ktor.client.cio)
            }
        }
        if (HostManager.hostIsMac) {
            val iosSimulatorArm64Main by getting {
                dependencies {
                    api(libs.ktor.client.darwin)
                }
            }
        }
    }
}
