@file:Suppress("UnstableApiUsage")

rootProject.name = "wasmline-plugin-test"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

val useWasmlineSource = providers.gradleProperty("wasmline.source").orNull != "false"

pluginManagement {
    if (providers.gradleProperty("wasmline.source").orNull != "false") {
        includeBuild("..")
    }
    repositories {
        mavenLocal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
    repositories {
        mavenLocal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        maven("https://packages.jetbrains.team/maven/p/kpm/public/") {
            mavenContent {
                includeGroup("org.jetbrains.jewel")
            }
        }
    }
}

if (useWasmlineSource) {
    includeBuild("..")
}

