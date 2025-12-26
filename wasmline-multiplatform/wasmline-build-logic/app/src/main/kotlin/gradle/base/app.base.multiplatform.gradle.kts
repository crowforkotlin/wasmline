import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("kotlin-multiplatform")
}


kotlin {
    jvmToolchain(17)

    jvm("desktop")

    /*listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = Config.getBaseName(project)
            isStatic = true
        }
    }*/

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    js {
        browser {
            testTask { enabled = false }
            commonWebpackConfig { showProgress = true }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libsEx.`kotlinx-coroutines`)
            implementation(libsEx.`kotlinx-collections`)
        }
        androidMain.dependencies {
            implementation(libsEx.`kotlinx-coroutines-android`)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libsEx.`kotlinx-coroutines-swing`)
            }
        }
    }
}