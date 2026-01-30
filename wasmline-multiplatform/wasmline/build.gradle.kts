@file:Suppress("OPT_IN_USAGE", "unused", "UnstableApiUsage", "SpellCheckingInspection")

import com.android.build.api.dsl.androidLibrary
import com.android.build.gradle.internal.cxx.gradle.generator.externalNativeBuildIsActive
import com.android.kotlin.multiplatform.ide.models.serialization.androidSourceSetKey


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library.kmp)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {

    jvm()
    mingwX64()
    linuxX64()
    macosArm64()
    androidLibrary {
        namespace = "crow.mordecai.wasmline"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
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
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            configureCInterop(target)
            target.binaries.framework {
                isStatic = false
                freeCompilerArgs += listOf("-Xbinary=bundleId=crow.mordecai.wasmline")
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

    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

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
        val iosMain by creating { dependsOn(other = commonMain) }
        val iosArm64Main by getting { dependsOn(other = iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(other = iosMain) }
        val jvmMain by getting { dependsOn(other = jniMain)

        }
        val androidMain by getting {
            dependsOn(other = jniMain)
            dependencies {
                implementation(projects.wasmlineAndroid)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val hostTest by creating { dependsOn(other = commonTest) }
        val jvmTest by getting { dependsOn(other = hostTest) }
//        val androidInstrumentedTest by getting { dependsOn(other = hostTest) }
    }
}