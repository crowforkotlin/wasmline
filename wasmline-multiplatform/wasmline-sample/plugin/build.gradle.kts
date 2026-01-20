@file:Suppress("OPT_IN_USAGE")


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {

    wasmWasi {
        binaries.library()
    }

    sourceSets {
        wasmWasiMain.dependencies {
            implementation(projects.wasmlineSample.common)
            implementation(projects.wasmline.core)
            implementation(libs.okio.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.protobuf)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlin.stdlib)
            implementation(libs.kotlinx.coroutines)
        }
    }
}