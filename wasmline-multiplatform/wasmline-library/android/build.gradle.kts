@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.kotlin.android)
    id("app.base.library")
}

android {

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
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    dependencies {
        api(libs.androidx.core.ktx)
        api(libs.androidx.activity.ktx)
    }
}