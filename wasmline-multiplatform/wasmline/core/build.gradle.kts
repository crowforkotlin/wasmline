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
    iosArm64()
    iosX64()
    iosSimulatorArm64()
    tvosArm64()
    tvosSimulatorArm64()
    tvosX64()

    wasmWasi {
        nodejs()
        binaries.library()
    }

    applyDefaultHierarchyTemplate()

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

        val hostMain by creating { dependsOn(commonMain) }
        val jniMain by creating { dependsOn(other =hostMain) }

        val nativeMain by getting { dependsOn(other = hostMain) }
        val jvmMain by getting { dependsOn(other = jniMain) }
        val androidMain by getting { dependsOn(other = jniMain) }
        val androidNativeArm64Main by getting { dependsOn(nativeMain) }
        val androidNativeArm32Main by getting { dependsOn(nativeMain) }
        val androidNativeX64Main by getting { dependsOn(nativeMain) }
        val androidNativeX86Main by getting { dependsOn(nativeMain) }
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
            path = file("src/androidMain/cpp/CMakeLists.txt")
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