@file:Suppress("OPT_IN_USAGE", "unused", "UnstableApiUsage", "SpellCheckingInspection")

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
    android {
        namespace = "crow.wasmline"
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
    wasmWasi {
        nodejs()
        binaries.library()
    }
    apply {
        val iosBuildDir = project.file("build/ios")
        val wasmtimeLibDir = project.file("../../../platforms/ios/arm64/lib")
        val nativeHeaderDir = project.file("src/iosMain/native")
        val wasmtimeHeaderDir = project.file("../../../platforms/ios/arm64/include")
        val configureCInterop = { target: org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget ->
            target.compilations.getByName("main") {
                val wasmline by cinterops.creating {
                    defFile(project.file("src/iosMain/native/cinterop/wasmline.def"))
                    includeDirs(nativeHeaderDir)
                    includeDirs(wasmtimeHeaderDir)
                    compilerOpts("-I${nativeHeaderDir.absolutePath}", "-I${wasmtimeHeaderDir.absolutePath}")
                    val coreLibAbsPath = iosBuildDir.resolve("libwasmline_core_ios.a").absolutePath
                    val wasmtimeLibAbsPath = wasmtimeLibDir.resolve("libwasmtime.a").absolutePath
                    linkerOpts("-Wl,-force_load,${coreLibAbsPath}")
                    linkerOpts("-Wl,-force_load,${wasmtimeLibAbsPath}")
                    linkerOpts("-lc++")
                    linkerOpts("-framework", "CoreFoundation")
                    linkerOpts("-framework", "Security")
                }
            }
        }
        if (HostManager.hostIsMac) {
            listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
                configureCInterop(target)
                target.binaries.framework {
                    isStatic = false
                    freeCompilerArgs += listOf("-Xbinary=bundleId=crow.wasmline")
                    val coreLibAbsPath = iosBuildDir.resolve("libwasmline_core_ios.a").absolutePath
                    val wasmtimeLibAbsPath = wasmtimeLibDir.resolve("libwasmtime.a").absolutePath
                    linkerOpts(
                        "-Wl,-force_load,${coreLibAbsPath}",
                        "-Wl,-force_load,${wasmtimeLibAbsPath}",
                        "-lc++",
                        "-framework", "CoreFoundation",
                        "-framework", "Security"
                    )
                }
            }
        }
    }

    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    applyDefaultHierarchyTemplate()
    sourceSets {

        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.serialization.protobuf)
            }
        }
        val hostMain by creating { dependsOn(other = commonMain) }
        val jniMain by creating { dependsOn(other = hostMain) }
        val jvmMain by getting { dependsOn(other = jniMain) }
        val webMain by getting { dependsOn(other = hostMain) }
        val jsMain by getting { dependsOn(other = webMain) }
        val wasmJsMain by getting { dependsOn(other = webMain) }
//        val macosArm64Main by getting { dependsOn(hostMain) }
//        val linuxX64Main by getting { dependsOn(jvmMain) }
//        val mingwX64Main by getting { dependsOn(jvmMain) }
        val androidMain by getting {
            dependsOn(other = jniMain)
            dependencies {
                implementation(projects.wasmlineAndroid)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.coroutines.test)
                implementation(projects.wasmline)
            }
        }
        val hostTest by creating {
            dependsOn(other = commonTest)
            dependencies {
                implementation(projects.wasmlineLoader)
            }
        }
        val jvmTest by getting { dependsOn(other = hostTest) }

        if (HostManager.hostIsMac) {
            val iosMain by getting { dependsOn(other = hostMain) }
            val iosArm64Main by getting { dependsOn(other = iosMain) }
            val iosSimulatorArm64Main by getting { dependsOn(other = iosMain) }
            val iosTest by getting { dependsOn(other = hostTest) }
        }
//        val androidInstrumentedTest by getting { dependsOn(other = hostTest) }
    }
}