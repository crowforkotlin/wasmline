plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library.kmp)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
}

extra["wasmline.engine"] = "cranelift"
apply(from = rootProject.file("gradle/wasmline-engine.gradle.kts"))
