@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.app.base.android)
}

androidApplication {
    config(
        versionCode = 1,
        versionName = "1.0.0-release",
    ) {

    }
}

dependencies {
    api(libs.androidx.core.ktx)
    api(libs.androidx.activity.ktx)
    api(libs.androidx.activity.compose)
    api(libs.androidx.material)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.serialization.protobuf)

    api(projects.sampleCommon)
    api(projects.sampleApps.multiplatform.shared)

    api(libs.crow.wasmline)
    api(libs.crow.wasmline.loader)
    // Engine module provides native libwasmline.so for Android via variant publishing
    implementation(libs.crow.wasmline.engine.pulley)
    api(libs.ktor.client.okhttp)
}
