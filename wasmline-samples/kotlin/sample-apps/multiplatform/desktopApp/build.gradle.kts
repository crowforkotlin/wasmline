import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.jetbrains.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.jvm)
}

compose.desktop {
    application.mainClass = "crow.wasmline.sample.MainKt"
}

dependencies {
    implementation(projects.sampleApps.multiplatform.shared)
}