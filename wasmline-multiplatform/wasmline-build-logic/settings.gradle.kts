pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/") // mavenCentral snapshot repository
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/") // mavenCentral snapshot repository
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
rootProject.name = "wasmedge-build-logic"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include("base")
include("app")