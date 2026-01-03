@file:Suppress("OPT_IN_USAGE")


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

kotlin {

    wasmWasi {
        binaries.library()
    }

    sourceSets {
        wasmWasiMain.dependencies {
            implementation(projects.wasmline.core)
            implementation(libs.okio.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.protobuf)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlin.stdlib)
            implementation(libs.kotlinx.coroutines)
        }
    }
}

val copyWasmTask: TaskProvider<Task?>? = tasks.register("copyWasmArtifacts") {
    doLast {
        // 提取公共的源文件路径，避免重复写
        val wasmSourceDirs = files(
            paths = arrayOf(
                "build/compileSync/wasmWasi/main/productionExecutable/optimized",
                "build/compileSync/wasmWasi/main/productionLibrary/optimized"
            )
        )
        // 定义所有需要复制到的目标文件夹
        val destinationDirs = listOf(
            project(projects.wasmlineSample.android.path).file("src/androidMain/assets"),
            project(projects.wasmlineSample.multiplatform.path).file("src/androidMain/assets"),
            project(projects.wasmlineSample.multiplatform.path).file("src/desktopMain/resources")
        )

        // 遍历列表，执行复制
        destinationDirs.forEach { targetDir ->
            copy {
                from(wasmSourceDirs)
                include("*.wasm")
                rename { "plugin.wasm" }
                into(targetDir)
            }
            println("Wasm copied to: $targetDir")
        }
    }
}
tasks.configureEach {
    // 只要任务名包含这两个关键字，就在结束后执行复制，不再需要手动写两遍
    if (name.contains("KotlinWasmWasiOptimize")) {
        finalizedBy(copyWasmTask)
    }
}