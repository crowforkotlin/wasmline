@file:Suppress("OPT_IN_USAGE")

plugins {
    id("app.base.multiplatform.library")
    alias(libs.plugins.kotlin.serialization)
//    alias(libs.plugins.wasmline)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    wasmWasi {
        nodejs()
        binaries.library()
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.wasmline)
            implementation(libs.kotlinx.serialization.protobuf)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}