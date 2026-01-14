@file:Suppress("OPT_IN_USAGE")

plugins {
    id("app.base.library")
    id("app.base.multiplatform")
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

kotlin {
    wasmWasi { binaries.library() }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.protobuf)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}