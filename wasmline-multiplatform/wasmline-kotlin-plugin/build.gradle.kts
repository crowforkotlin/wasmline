@file:Suppress("OPT_IN_USAGE", "unused", "UnstableApiUsage")

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.maven.publish)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}


buildConfig {
    useKotlinOutput { internalVisibility = true }
    packageName("crow.mordecai.wasmline.kotlin")
    buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"${libs.plugins.wasmline.kotlin.get()}\"")
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