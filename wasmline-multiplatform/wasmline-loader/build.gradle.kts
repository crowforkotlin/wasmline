@file:Suppress("OPT_IN_USAGE", "unused", "UnstableApiUsage")

import org.jetbrains.kotlin.konan.target.HostManager


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library.kmp)
    alias(libs.plugins.maven.publish)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}


kotlin {
    jvm()
    androidLibrary {
        namespace = "crow.wasmline"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    if (HostManager.hostIsMac) {
        listOf(
            iosArm64(),
            iosSimulatorArm64()
        ).forEach { target ->
            target.binaries.framework {
                isStatic = false
                freeCompilerArgs
            }
        }
    }
    applyDefaultHierarchyTemplate()
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.wasmline)
                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.serialization.protobuf)
                implementation(libs.okio.core)
            }
        }
        val hostMain by creating { dependsOn(other = commonMain) }
        val jniMain by creating { dependsOn(other = hostMain) }
        val androidMain by getting { dependsOn(other = jniMain) }
        val jvmMain by getting { dependsOn(other = jniMain) }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val hostTest by creating { dependsOn(other = commonTest) }
        val jvmTest by getting {
            dependsOn(other = hostTest)
        }

        if (HostManager.hostIsMac) {
            val iosMain by getting { dependsOn(other = hostMain) }
        }
    }
}


dependencies { }