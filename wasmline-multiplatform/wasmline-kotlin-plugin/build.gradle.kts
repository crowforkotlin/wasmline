@file:Suppress("OPT_IN_USAGE", "unused", "UnstableApiUsage")

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension


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
    packageName("crow.wasmline.kotlin")
    buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"${libs.plugins.wasmline.kotlin.get()}\"")
}

dependencies {

    compileOnly(kotlin("compiler-embeddable"))
    compileOnly(kotlin("stdlib"))

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

/**
 * Configures the project for Maven publishing using the MavenPublishBaseExtension.
 * * This setup defines the publication platform as Kotlin JVM and provides
 * an empty Javadoc JAR to satisfy repository requirements while optimizing
 * build time.
 *
 * After running the 'publishToMavenLocal' task, this artifact will be
 * available at: ~/.m2/repository/crow/wasmline/wasmline-kotlin-plugin/
 */
configure<MavenPublishBaseExtension> {
    configure(
        platform = KotlinJvm(
            javadocJar = JavadocJar.Empty()
        )
    )
}
