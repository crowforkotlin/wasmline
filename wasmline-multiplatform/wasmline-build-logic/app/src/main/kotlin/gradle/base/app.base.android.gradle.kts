import buildlogic.Config
import buildlogic.libsEx

import extensions.AndroidExtension
import kotlin.jvm.java

plugins { id("com.android.application") }

/**
 * This plugin encapsulates most template configuration; remaining configuration is provided by [AndroidExtension]
 */

extensions.create("androidApplication", AndroidExtension::class.java, project)

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
        applicationId = Config.ApplicationId
        minSdk = libsEx.versions.`android-minSdk`.requiredVersion.toInt()
        targetSdk = libsEx.versions.`android-targetSdk`.requiredVersion.toInt()
    }
    buildFeatures { viewBinding = true }
    packaging {
        resources {
            pickFirsts += arrayOf(
                "META-INF/androidx.compose.ui_ui.version"
            )
            excludes += arrayOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "kotlin/**",
                "META-INF/*.version",
                "META-INF/**/LICENSE.txt",
                "/META-INF/{AL2.0,LGPL2.1}"
            )
        }
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
