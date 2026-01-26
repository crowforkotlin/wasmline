import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("app.base.android")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.application)
}

dependencies {
    implementation(projects.wasmlineSample.multiplatform.shared)
    implementation(libs.androidx.activity.compose)
}
