plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}


java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

application { mainClass = "crow.wazero.wasmline.AppKt" }

dependencies { }