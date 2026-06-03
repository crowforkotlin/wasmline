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
        namespace = "crow.wasmline.loader"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    wasmJs {
        browser()
        binaries.library()
    }
    js {
        browser()
        binaries.library()
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

    // Kotlin Friend Modules: declare wasmline (core) as friend for internal access.
    // Use jar/klib file paths (not directories) for -Xfriend-paths.
    val coreProject = project(":wasmline")
    val coreBuildDir = coreProject.layout.buildDirectory.asFile.get()
    val friendPathsValue = listOf(
        File(coreBuildDir, "libs/wasmline-jvm-1.0.0.jar").absolutePath,
    ).joinToString(",")
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xfriend-paths=$friendPathsValue")
                }
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
        val webMain by getting { dependsOn(other = hostMain) }
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