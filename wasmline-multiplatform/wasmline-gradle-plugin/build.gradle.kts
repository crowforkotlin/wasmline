@file:Suppress("OPT_IN_USAGE", "unused", "UnstableApiUsage")

plugins {
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.java.gradle.plugin)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.buildconfig)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}


buildConfig {
    useKotlinOutput { internalVisibility = true }
    packageName("crow.mordecai.wasmline.gradle")
    val compilerPlugin = projects.wasmlineKotlinPlugin
    buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"${libs.plugins.wasmline.kotlin.get()}\"")
    buildConfigField("String", "KOTLIN_PLUGIN_GROUP", "\"${compilerPlugin.group}\"")
    buildConfigField("String", "KOTLIN_PLUGIN_NAME", "\"${compilerPlugin.name}\"")
    buildConfigField("String", "KOTLIN_PLUGIN_VERSION", "\"${compilerPlugin.version}\"")
}

dependencies {

    implementation(projects.wasmline)
    implementation(projects.wasmlineLoader)
    implementation(libs.gradle.kotlin.plugin)
    implementation(libs.gradle.kotlin.plugin.api)

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

gradlePlugin {
    plugins {
        create("wasmline") {
            id = "crow.mordecai.wasmline"
            displayName = "wasmline"
            description = "wasmline desc"
            implementationClass = "com.mordecai.wasmline.WasmlinePlugin"
        }
    }
}