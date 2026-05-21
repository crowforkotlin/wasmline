plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.wasmline)
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application { mainClass = "crow.wasmline.sample.application.MainKt" }

dependencies {
    implementation(libs.crow.wasmline.loader)
    implementation(project(":sample-common"))
}
