plugins {
    alias(libs.plugins.app.base.library)
    alias(libs.plugins.maven.publish)
}

android {
    defaultConfig {
        ndk { abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) }
    }
    externalNativeBuild {
        cmake {
            path = file("src/androidMain/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}