plugins {
    id("app.base.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    buildFeatures {
        compose = true
    }
    dependencies {
        debugImplementation(libsEx.`androidx-compose-ui-tooling`)
    }
}