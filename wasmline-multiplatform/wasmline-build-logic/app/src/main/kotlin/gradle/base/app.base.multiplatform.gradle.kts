import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("kotlin-multiplatform")
}


kotlin {
    jvmToolchain(17)

    jvm("desktop")

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            isStatic = true
            baseName = Config.getBaseName(project)
        }
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
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