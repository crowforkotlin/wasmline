plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
}

dependencies {
    api(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.compress.apache.common)
    api(libs.okio.core)
    api(projects.wasmlineLoader)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.compiler.embeddable)
}

kotlin {
    jvmToolchain(21)
}
