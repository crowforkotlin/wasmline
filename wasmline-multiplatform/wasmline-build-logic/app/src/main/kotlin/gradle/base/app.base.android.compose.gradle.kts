import gradle.kotlin.dsl.accessors._c8e23648a0123cabac06a951e2864907.debugImplementation
import libsEx

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