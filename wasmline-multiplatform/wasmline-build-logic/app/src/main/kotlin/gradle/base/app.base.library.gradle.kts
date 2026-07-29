import buildlogic.Config
import buildlogic.libsEx

plugins {
    id("com.android.library")
}

android {
    namespace = Config.getNamespace(project)
    compileSdk = libsEx.versions.`android-compileSdk`.requiredVersion.toInt()
    sourceSets {
        named("main") {
            manifest.srcFile("src/androidMain/AndroidManifest.xml")
            res.srcDirs("src/androidMain/res")
            resources.srcDirs("src/commonMain/resources")
            assets.srcDirs("src/androidMain/assets")
            kotlin.srcDirs("src/androidMain/kotlin")
        }
    }
    defaultConfig {
        minSdk = libsEx.versions.`android-minSdk`.requiredVersion.toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
