plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.wasmline)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application { mainClass = "crow.wasmline.sample.application.MainKt" }

dependencies {
    implementation(libs.crow.wasmline.loader)
    implementation(libs.crow.wasmline.network.ktor)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(projects.sampleCommon)

}
