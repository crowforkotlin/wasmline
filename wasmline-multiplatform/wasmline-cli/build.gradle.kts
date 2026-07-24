@file:Suppress("OPT_IN_USAGE", "unused", "UnstableApiUsage")

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.ktlint)
    application
}

// Exclude auto-generated sources from KtLint checks
ktlint {
    filter {
        exclude("**/generated/**")
    }
}

buildConfig {
    useKotlinOutput { internalVisibility = true }
    packageName("crow.wasmline.cli")
    buildConfigField("String", "VERSION", "\"${version}\"")
    buildConfigField("String", "WASMTIME_VERSION", "\"${extra["wasmtime.version"]}\"")
}

dependencies {
    implementation(projects.wasmline)
    implementation(projects.wasmlineLoader)

    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)

    implementation(libs.compress.apache.common)
    implementation(libs.compress.tukaani.xz)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    implementation(libs.okio.core)

    testImplementation(libs.kotlin.test)
}

application { mainClass = "crow.wasmline.cli.MainKt" }
