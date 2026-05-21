@file:Suppress("UnstableApiUsage")

rootProject.name = "wasmline-sample"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
pluginManagement {
    includeBuild("../../wasmline-multiplatform/wasmline-build-logic")
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
includeBuild("../../wasmline-multiplatform") {
    dependencySubstitution {
        substitute(module("crow.wasmline:wasmline")).using(project(":wasmline"))
        substitute(module("crow.wasmline:wasmline-loader")).using(project(":wasmline-loader"))
        substitute(module("crow.wasmline:wasmline-kotlin-plugin")).using(project(":wasmline-kotlin-plugin"))
    }
}
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            this.from(files("../../wasmline-multiplatform/gradle/libs.versions.toml")
            )
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

/////////////  Auto include module  ///////////

// When you need to delete a module, write this, and you will no longer include it, just write the module name
val excludeList: List<String> = listOf()
fun includeModule(topName: String, file: File) {
    if (!file.resolve(relative = "settings.gradle.kts").exists()) {
        if (file.resolve(relative = "build.gradle.kts").exists() && !excludeList.contains(file.name)) {
            var path = ""
            var nowFile = file
            while (nowFile.name != topName) {
                path = ":${nowFile.name}$path"
                nowFile = nowFile.parentFile
            }
            path = "${topName}$path"
            include(path)
        }
    }
    file.listFiles()?.filter {
        it.name != "src"
                && it.name != "build"
                && it.name != "iosApp"
                && !it.resolve("settings.gradle.kts").exists()
                && !excludeList.contains(it.name)
    }?.forEach {
        includeModule(topName, it)
    }
}

includeModule(topName = "sample-apps", file = file("sample-apps"))
include(":sample-common")
include(":sample-plugin")
