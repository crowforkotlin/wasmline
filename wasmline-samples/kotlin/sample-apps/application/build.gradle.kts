plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}


java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application { mainClass = "crow.wasmline.wasmline.AppKt" }

dependencies { }