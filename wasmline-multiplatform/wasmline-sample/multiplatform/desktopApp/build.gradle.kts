import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.jvm)
}

compose.desktop {
    application.mainClass = "crow.mordecai.wasmline.sample.MainKt"
}

dependencies {
    implementation(projects.wasmlineSample.multiplatform.shared)
}