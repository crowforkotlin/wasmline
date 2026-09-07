@file:Suppress("OPT_IN_USAGE", "unused", "UnstableApiUsage")

import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.tasks.Jar


plugins {
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.java.gradle.plugin)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.dokka)
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
    buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"${libs.plugins.wasmline.kotlin.get()}\"")
    buildConfigField("String", "KOTLIN_PLUGIN_GROUP", "\"${compilerPlugin.group}\"")
    buildConfigField("String", "KOTLIN_PLUGIN_NAME", "\"${compilerPlugin.name}\"")
    buildConfigField("String", "KOTLIN_PLUGIN_VERSION", "\"${compilerPlugin.version}\"")
}

dependencies {

    compileOnly(libs.kotlin.compiler.embeddable)
    compileOnly(projects.wasmlinePluginCore)

    implementation(projects.wasmlineLoader)
    implementation(libs.gradle.kotlin.plugin)
    implementation(libs.gradle.kotlin.plugin.api)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.compress.apache.common)
    implementation(libs.okio.core)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)

    testImplementation(libs.kotlin.test)
    testImplementation(projects.wasmlinePluginCore)
}

kotlin {
    explicitApi()
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
        optIn.add("crow.wasmline.plugin.core.InternalWasmlineToolingApi")
    }
}

evaluationDependsOn(":wasmline-plugin-core")

val pluginCoreOutput = project(":wasmline-plugin-core")
    .extensions
    .getByType<SourceSetContainer>()["main"]
    .output

tasks.named<Jar>("jar") {
    from(pluginCoreOutput)
}

gradlePlugin {
    plugins {
        create("wasmline") {
            id = "crow.wasmline"
            displayName = "wasmline"
            description = "wasmline desc"
            implementationClass = "crow.wasmline.WasmlinePlugin"
        }
        create("wasmlineRuntime") {
            id = "crow.wasmline.runtime"
            displayName = "Wasmline runtime resolver"
            description = "Selects the native Wasmline engine artifact for the current JVM host"
            implementationClass = "crow.wasmline.WasmlineRuntimePlugin"
        }
    }
}

configure<MavenPublishBaseExtension> {
    configure(
        platform = GradlePlugin(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
        ),
    )
}
