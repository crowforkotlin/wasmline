@file:Suppress("OPT_IN_USAGE", "unused", "UnstableApiUsage")

import org.gradle.kotlin.dsl.invoke


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("com.android.library")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {


    jvm()
    androidTarget()
    androidNativeArm64()
    androidNativeArm32()
    androidNativeX64()
    androidNativeX86()

    mingwX64()
    linuxX64()
    macosX64()
    macosArm64()

    apply {
        val iosBuildDir = project.file("build/ios")
        val wasmtimeLibDir = project.file("../../../platforms/ios/arm64/lib")
        val nativeHeaderDir = project.file("src/iosMain/native")
        val wasmtimeHeaderDir = project.file("../../../platforms/ios/arm64/include")
        val configureCInterop = { target: org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget ->
            target.compilations.getByName("main") {
                val wasmline by cinterops.creating {
                    defFile(project.file("src/nativeInterop/cinterop/wasmline.def"))
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
        listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
            configureCInterop(target)
            target.binaries.framework {
                isStatic = false
                freeCompilerArgs += listOf("-Xbinary=bundleId=crow.mordecai.wasmline.core")
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

    tvosArm64()
    tvosSimulatorArm64()
    tvosX64()

    wasmWasi { binaries.library() }

    applyDefaultHierarchyTemplate()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {

        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.serialization.protobuf)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val hostMain by creating {
            dependsOn(other = commonMain)
        }
        val hostTest by creating {
            dependsOn(other = commonTest)
        }
        val jniMain by creating { dependsOn(other = hostMain) }

        val nativeMain by getting { dependsOn(other = hostMain) }
        val jvmMain by getting { dependsOn(other = jniMain) }
        val androidMain by getting { dependsOn(other = jniMain) }

        val iosSimulatorArm64Main by getting {  }
        val iosArm64Main by getting {  }

        val wasmWasiMain by getting {
            dependencies {
                implementation(libs.okio.core)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kotlin.stdlib)
            }
        }
    }
}

android {
    namespace = "crow.mordecai.wasmline"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].resources.srcDirs("src/commonMain/resources")

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags("")
                abiFilters("arm64-v8a")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/androidMain/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}