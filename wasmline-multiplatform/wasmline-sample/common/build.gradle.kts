@file:Suppress("OPT_IN_USAGE")

import com.android.utils.TraceUtils.simpleId


plugins {
    id("app.base.multiplatform.library")
    alias(libs.plugins.kotlin.serialization)
}

if (gradle.extra["wasmlineAvailable"] as? Boolean == true) {
    apply(plugin = "crow.wasmline")
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