@file:Suppress("OPT_IN_USAGE", "unused", "UnstableApiUsage")

import com.android.build.api.dsl.androidLibrary


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
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { target ->
        target.binaries.framework {
            isStatic = false
            freeCompilerArgs
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.serialization.protobuf)
                implementation(libs.okio.core)
            }
        }
        val jniMain by creating { dependsOn(other = commonMain) }
        val androidMain by getting { dependsOn(other = jniMain) }
        val jvmMain by getting { dependsOn(other = jniMain) }
        
        val iosMain by creating { dependsOn(other = commonMain) }
        val iosArm64Main by getting { dependsOn(other = iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(other = iosMain) }
    }
}


dependencies { }