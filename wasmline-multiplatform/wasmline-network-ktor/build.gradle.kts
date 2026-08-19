@file:Suppress("OPT_IN_USAGE", "unused", "UnstableApiUsage")

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library.kmp)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
}

configure<MavenPublishBaseExtension> {
    configure(
        platform = KotlinMultiplatform(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            androidVariantsToPublish = emptyList(),
        ),
    )
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvm()
    android {
        namespace = "crow.wasmline.network.ktor"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    if (HostManager.hostIsMac) {
        listOf(iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
            target.binaries.framework {
                isStatic = false
            }
        }
    }
    listOf(linuxArm64(), linuxX64(), mingwX64())
    applyDefaultHierarchyTemplate()
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.wasmlineLoader)
                api(libs.ktor.client.core)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        if (HostManager.hostIsMac) {
            val appleMain by getting {
                dependencies {
                    implementation(libs.ktor.client.darwin)
                }
            }
        }
        val linuxMain by getting {
            dependencies {
                implementation(libs.ktor.client.curl)
            }
        }
        val mingwMain by getting {
            dependencies {
                implementation(libs.ktor.client.winhttp)
            }
        }
    }
}
