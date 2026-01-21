@file:Suppress("OPT_IN_USAGE", "unused", "UnstableApiUsage")

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.buildconfig)
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}


buildConfig {
    useKotlinOutput { internalVisibility = true }
    packageName("crow.mordecai.wasmline.cli")
    buildConfigField("String", "VERSION", "\"${version}\"")
}

dependencies {
    implementation(projects.wasmline)

    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)

    testImplementation(libs.kotlin.test)
}

application { mainClass = "com.mordecai.wasmline.cli.MainKt" }