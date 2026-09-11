@file:Suppress("UnstableApiUsage")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("../../")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
includeBuild("../../")
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}
rootProject.name = "minimal-raw-export"
include(":plugin")
include(":common", ":desktopApp", ":androidApp", ":webApp", ":minimal-library")
project(":minimal-library").projectDir = file("../minimal-library")
