import org.gradle.kotlin.dsl.provider.gradleKotlinDslJarsOf

rootProject.name = "wasmline-multiplatform"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
pluginManagement {
    includeBuild("wasmline-build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://packages.jetbrains.team/maven/p/kpm/public/") {
            mavenContent {
                includeGroup("org.jetbrains.jewel")
            }
        }
    }
}

/////////////  自动 include 模块  ///////////

// 需要删除模块时写这里面，将不再进行 include，直接写模块名即可
val excludeList: List<String> = listOf()

fun includeModule(topName: String, file: File) {
    if (!file.resolve("settings.gradle.kts").exists()) {
        if (file.resolve("build.gradle.kts").exists() && !excludeList.contains(file.name)) {
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
    // 递归寻找所有子模块
    file.listFiles()?.filter {
        it.name != "src" // 去掉 src 文件夹
                && it.name != "build"
                && it.name != "iosApp"
                && !it.resolve("settings.gradle.kts").exists() // 去掉独立的项目模块，比如 build-logic
                && !excludeList.contains(it.name) // 去掉被忽略的模块
    }?.forEach {
        includeModule(topName, it)
    }
}



include(":wasmline")
includeModule(topName = "wasmline-cli", file = file("wasmline-cli"))
includeModule(topName = "wasmline-sample", file = file("wasmline-sample"))
//includeModule(topName = "wasmline", file = file("wasmline"))
