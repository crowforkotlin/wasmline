@file:Suppress("PackageDirectoryMismatch", "unused", "UnstableApiUsage")

import com.android.build.api.dsl.androidLibrary


/** @formatter:off */


plugins {
    id("kotlin-multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {

    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }

    androidLibrary {
        namespace = Config.getNamespace(project)
        compileSdk = libsEx.versions.`android-compileSdk`.requiredVersion.toInt()
        minSdk = libsEx.versions.`android-minSdk`.requiredVersion.toInt()
    }

    jvm(name = "desktop")

    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            isStatic = true
            baseName = Config.getBaseName(project)
        }
    }

    sourceSets {
        val commonMain by  getting {
            dependencies {
                api(libsEx.`kotlinx-coroutines`)
                api(libsEx.`kotlinx-collections`)
            }
        }
        val androidMain by getting {
            dependencies {
                api(libsEx.`kotlinx-coroutines-android`)
            }
        }
        val desktopMain by getting {
            dependencies {
                api(libsEx.`kotlinx-coroutines-swing`)
            }
        }
    }
}

/*
android {
    namespace = Config.getNamespace(project)
    compileSdk = libsEx.versions.`android-compileSdk`.requiredVersion.toInt()
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].resources.srcDirs("src/commonMain/resources")
    defaultConfig { minSdk = libsEx.versions.`android-minSdk`.requiredVersion.toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}*/
