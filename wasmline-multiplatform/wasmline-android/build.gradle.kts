plugins { id("app.base.library") }

android {
    defaultConfig {
        ndk { abiFilters.add("arm64-v8a") }
    }
    externalNativeBuild {
        cmake {
            path = file("src/androidMain/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}