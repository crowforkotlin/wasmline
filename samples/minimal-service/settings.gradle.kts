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
rootProject.name = "minimal-service"
include(":plugin")
include(":shared", ":desktopApp", ":androidApp", ":webApp", ":minimal-support")
project(":minimal-support").projectDir = file("../minimal-support")
include(":contract")
