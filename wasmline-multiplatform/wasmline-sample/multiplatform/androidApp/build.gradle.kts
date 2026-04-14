import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.app.base.android)
    alias(libs.plugins.jetbrains.compose.compiler)
}

dependencies {
    implementation(projects.wasmlineSample.multiplatform.shared)
    implementation(libs.androidx.activity.compose)
}
