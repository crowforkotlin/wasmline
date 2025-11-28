plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
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
        api(libs.kotlinx.serialization.json)
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

afterEvaluate {
    tasks.named<com.android.build.gradle.tasks.MergeSourceSetFolders>("mergeDebugAssets") {
        // Use the absolute path (starting with ':')
        dependsOn(rootProject.tasks.getByPath("wasmtime-core:plugin:wasmCopy"))
    }
    tasks.named("assembleDebug") {
        dependsOn(rootProject.tasks.getByPath("wasmtime-core:plugin:compileProductionExecutableKotlinWasmWasiOptimize"))
    }
}
