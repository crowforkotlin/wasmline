plugins {
    alias(libs.plugins.kotlin.android)
    id("app.base.android")
}

androidApplication {
    config(
        versionCode = 1,
        versionName = "1.0.0-release",
    ) {
        api(libs.androidx.core.ktx)
        api(libs.androidx.activity.ktx)
        api(libs.androidx.material)
    }
}

android {

    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags("")
                abiFilters("arm64-v8a")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}