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

dependencies { implementation(libs.clikt) }

application { mainClass = "com.mordecai.wasmline.cli.MainKt" }