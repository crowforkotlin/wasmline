@file:Suppress("UnstableApiUsage")

rootProject.name = "wasmline-sample"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
pluginManagement {
    includeBuild("../../wasmline-multiplatform/wasmline-build-logic")
    val useIncludedBuild = System.getenv("WASMLINE_USE_INCLUDED_BUILD")?.let { it == "1" } ?: true
    if (useIncludedBuild) {
        includeBuild("../../wasmline-multiplatform")
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
if (System.getenv("WASMLINE_USE_INCLUDED_BUILD")?.let { it == "1" } ?: true) {
    includeBuild("../../wasmline-multiplatform")
}
// Use Maven local artifacts instead of includeBuild source dependency
// includeBuild("../../wasmline-multiplatform") {
//     dependencySubstitution {
//         substitute(module("crow.wasmline:wasmline")).using(project(":wasmline"))
//         substitute(module("crow.wasmline:wasmline-loader")).using(project(":wasmline-loader"))
//         substitute(module("crow.wasmline:wasmline-kotlin-plugin")).using(project(":wasmline-kotlin-plugin"))
//         substitute(module("crow.wasmline:wasmline-network-ktor")).using(project(":wasmline-network-ktor"))
//     }
// }
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
include(":sample-component-fixture")
include(":sample-component-export-plugin")
include(":sample-component-plugin")
include(":sample-plugin")
include(":sample-raw-export-plugin")
