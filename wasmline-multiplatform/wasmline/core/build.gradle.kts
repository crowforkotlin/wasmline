@file:Suppress("OPT_IN_USAGE", "unused", "UnstableApiUsage")

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("com.android.library")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
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
    iosX64()
    // 1. 定义路径 (保持不变)
    val iosBuildDir = project.file("build/ios")
    val wasmtimeLibDir = project.file("../../../platforms/ios/lib")
    val nativeHeaderDir = project.file("src/iosMain/native")
    val wasmtimeHeaderDir = project.file("../../../platforms/ios/include")

    val configureCInterop = { target: org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget ->
        target.compilations.getByName("main") {
            val wasmline by cinterops.creating {
                defFile(project.file("src/nativeInterop/cinterop/wasmline.def"))

                // 2. 这里的 includeDirs 建议保留！
                // 为什么？因为 cinterop 工具在读取 .def 的 headers = WasmlineNative.h 这一行时，
                // 它需要第一时间找到这个 .h 文件。
                // 虽然 .def 里写了 compilerOpts -I，但有时候为了保险，告诉 Gradle 入口在哪里更好。
                // 这一行是为了让 "headers = ..." 能生效
                includeDirs(project.file("src/iosMain/native"))
            }
        }
    }

    // 应用到 iOS Targets (根据你的项目实际开启的 Target)
    iosArm64 { configureCInterop(this) }
    iosSimulatorArm64 { configureCInterop(this)

    }
    tvosArm64()
    tvosSimulatorArm64()
    tvosX64()

    wasmWasi {
        nodejs()
        binaries.library()
    }

    applyDefaultHierarchyTemplate()

    compilerOptions {
        // actual scope is unstable, cancel compilation warning [https://youtrack.jetbrains.com/issue/KT-61573]
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {

        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines)
            }
        }
        val wasmWasiMain by getting {
            dependencies {
                implementation(libs.okio.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.serialization.protobuf)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kotlin.stdlib)
            }
        }

        val hostMain by creating { dependsOn(other = commonMain) }
        val jniMain by creating { dependsOn(other = hostMain) }

        val nativeMain by getting { dependsOn(other = hostMain) }
        val jvmMain by getting { dependsOn(other = jniMain) }
        val androidMain by getting { dependsOn(other = jniMain) }


        val iosSimulatorArm64Main by getting {  }
        val iosArm64Main by getting {  }

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

tasks.register<Copy>("wasmCopy") {
    val assetsDir = rootProject.file("${projects.wasmlineSample.android.path.replace(":","/")}/src/androidMain/assets")
    File(assetsDir, "plugin.wasm").deleteOnExit()
    from(file("build/compileSync/wasmWasi/main/productionExecutable/optimized"))
    include("*.wasm")
    rename { "plugin.wasm" }
    into(assetsDir)
}
tasks.findByName("compileProductionExecutableKotlinWasmWasiOptimize")?.finalizedBy("wasmCopy")
tasks.findByName("compileProductionLibraryKotlinWasmWasiOptimize")?.finalizedBy("wasmCopy")