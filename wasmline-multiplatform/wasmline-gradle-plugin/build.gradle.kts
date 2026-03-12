@file:Suppress("OPT_IN_USAGE", "unused", "UnstableApiUsage")

import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension


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
    packageName("crow.wasmline.gradle")
    val compilerPlugin = projects.wasmlineKotlinPlugin
    println("""
        ---------------------------------
        group is : ${compilerPlugin.group}
        name is : ${compilerPlugin.name}
        version is : ${compilerPlugin.version}
        id is : ${libs.plugins.wasmline.kotlin.get()}
        ---------------------------------
    """.trimIndent())
    buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"${libs.plugins.wasmline.kotlin.get()}\"")
    buildConfigField("String", "KOTLIN_PLUGIN_GROUP", "\"${compilerPlugin.group}\"")
    buildConfigField("String", "KOTLIN_PLUGIN_NAME", "\"${compilerPlugin.name}\"")
    buildConfigField("String", "KOTLIN_PLUGIN_VERSION", "\"${compilerPlugin.version}\"")
}

dependencies {

    compileOnly(libs.kotlin.compiler.embeddable)

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
            id = "crow.wasmline"
            displayName = "wasmline"
            description = "wasmline desc"
            implementationClass = "crow.wasmline.WasmlinePlugin"
        }
    }
}

configure<MavenPublishBaseExtension> {
    configure(
        platform = GradlePlugin(
            javadocJar = JavadocJar.Empty()
        )
    )
}