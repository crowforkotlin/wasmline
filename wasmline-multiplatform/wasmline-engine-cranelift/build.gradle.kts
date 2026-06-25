@file:Suppress("OPT_IN_USAGE", "unused", "UnstableApiUsage")

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library.kmp)
    alias(libs.plugins.maven.publish)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvm()
    android {
        namespace = "crow.wasmline.engine.cranelift"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        // Cranelift only supports 64-bit architectures (no armeabi-v7a or x86)
        defaultConfig {
            ndk { abiFilters.addAll(listOf("arm64-v8a", "x86_64")) }
        }
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        val commonMain by getting {
            dependencies {
                compileOnly(projects.wasmline)
            }
        }
    }
}

// Capability conflict: only ONE engine module can be on the classpath
configurations.all {
    outgoing {
        capability("crow.wasmline:wasmline-engine:${project.version}")
    }
}
