@file:Suppress("OPT_IN_USAGE", "unused", "UnstableApiUsage")

import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.tasks.Jar

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
    compileOnly(projects.wasmlinePluginCore)
    implementation(projects.wasmlineLoader)

    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.compress.apache.common)
    implementation(libs.okio.core)

    testImplementation(libs.kotlin.test)
    testImplementation(projects.wasmlinePluginCore)
}

evaluationDependsOn(":wasmline-plugin-core")

val pluginCoreOutput = project(":wasmline-plugin-core")
    .extensions
    .getByType<SourceSetContainer>()["main"]
    .output

tasks.named<Jar>("jar") {
    from(pluginCoreOutput)
}

application { mainClass = "crow.wasmline.cli.MainKt" }
