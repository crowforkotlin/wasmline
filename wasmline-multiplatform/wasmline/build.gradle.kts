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
        val nativeHeaderDir = project.file("src/iosMain/native")
        val wasmtimeVersion = project.property("wasmtime.version") as String
        val wasmtimeReleaseTag = "release-v$wasmtimeVersion"

        // iOS only supports pulley engine variant
        fun iosPlatformRoot(targetName: String) = when (targetName) {
            "iosSimulatorArm64" -> project.file("../../build/platforms/$wasmtimeReleaseTag/pulley/ios/simulator-arm64")
            else -> project.file("../../build/platforms/$wasmtimeReleaseTag/pulley/ios/arm64")
        }
        fun iosBuildDir(targetName: String) = when (targetName) {
            "iosSimulatorArm64" -> project.file("build/ios/simulator-arm64")
            else -> project.file("build/ios/arm64")
        }
        fun buildNativeBridgeTask(target: org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget) = tasks.register(
            "build${target.name.replaceFirstChar { it.uppercaseChar() }}NativeBridge",
            Exec::class.java,
        ) {
            workingDir = project.projectDir
            commandLine(
                "bash",
                "../ci/compile-ios.sh",
                if (target.name == "iosSimulatorArm64") "simulator-arm64" else "arm64",
            )
        }
        val configureCInterop = { target: org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget ->
            val platformRoot = iosPlatformRoot(target.name)
            val wasmtimeHeaderDir = platformRoot.resolve("include")
            val wasmtimeLibDir = platformRoot.resolve("lib")
            val coreLibAbsPath = iosBuildDir(target.name).resolve("libwasmline_core_ios.a").absolutePath
            val wasmtimeLibAbsPath = wasmtimeLibDir.resolve("libwasmtime.a").absolutePath
            target.compilations.getByName("main") {
                val wasmline by cinterops.creating {
                    defFile(project.file("src/iosMain/native/cinterop/wasmline.def"))
                    includeDirs(nativeHeaderDir)
                    includeDirs(wasmtimeHeaderDir)
                    compilerOpts("-I${nativeHeaderDir.absolutePath}", "-I${wasmtimeHeaderDir.absolutePath}")
                    linkerOpts("-Wl,-force_load,$coreLibAbsPath")
                    linkerOpts("-Wl,-force_load,$wasmtimeLibAbsPath")
                    linkerOpts("-lc++")
                    linkerOpts("-framework", "CoreFoundation")
                    linkerOpts("-framework", "Security")
                }
            }
        }
        if (HostManager.hostIsMac) {
            listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
                val nativeBridgeTask = buildNativeBridgeTask(target)
                val coreLibAbsPath = iosBuildDir(target.name).resolve("libwasmline_core_ios.a").absolutePath
                val wasmtimeLibAbsPath = iosPlatformRoot(target.name).resolve("lib/libwasmtime.a").absolutePath
                configureCInterop(target)
                target.binaries.framework {
                    isStatic = false
                    freeCompilerArgs += listOf("-Xbinary=bundleId=crow.wasmline")
                    linkerOpts(
                        "-Wl,-force_load,$coreLibAbsPath",
                        "-Wl,-force_load,$wasmtimeLibAbsPath",
                        "-lc++",
                        "-framework",
                        "CoreFoundation",
                        "-framework",
                        "Security",
                    )
                    linkTaskProvider.configure {
                        dependsOn(nativeBridgeTask)
                    }
                }
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
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
        // jsMain/wasmJsMain already depend on webMain via the default hierarchy template.
        val webMain by getting { dependsOn(other = hostMain) }
//        val macosArm64Main by getting { dependsOn(hostMain) }
//        val linuxX64Main by getting { dependsOn(jvmMain) }
//        val mingwX64Main by getting { dependsOn(jvmMain) }
        val androidMain by getting {
            dependsOn(other = jniMain)
            dependencies {
                implementation(projects.wasmlineAndroid)
            }
        }

        // commonTest: base test dependencies available on ALL platforms (including wasmWasi)
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        
        // hostTest: extends commonTest with WasmlineLoader (only for host platforms)
        // WASMLINE LOADER IS HOST-SPECIFIC: It handles plugin loading, validation, and platform-specific artifact resolution
        // This includes: JVM, JS, Android, iOS, Desktop targets
        // EXCLUDES: wasmWasi (pure WASM runtime, not a host environment for loading other plugins)
        val hostTest by creating {
            dependsOn(other = commonTest)
            dependencies {
                implementation(projects.wasmlineLoader)
            }
        }
        
        // JVM-specific JNI tests (uses native Wasmtime library loaded via JNI)
        val jvmTest by getting { 
            dependsOn(other = hostTest)
            dependencies {
                implementation(projects.wasmlineEngineCranelift) // Provides libwasmline.so for testing
            }
        }
        
        if (HostManager.hostIsMac) {
            val iosMain by getting { dependsOn(other = hostMain) }
            val iosArm64Main by getting { dependsOn(other = iosMain) }
            val iosSimulatorArm64Main by getting { dependsOn(other = iosMain) }
            val iosTest by getting { dependsOn(other = hostTest) }
        }
//        val androidInstrumentedTest by getting { dependsOn(other = hostTest) }
    }
}
