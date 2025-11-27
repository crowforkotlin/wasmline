plugins {
  `kotlin-dsl`
}

dependencies {
  implementation(projects.base)
  implementation(libs.gradle.android.plugin)
  implementation(libs.gradle.kotlin.plugin)
  implementation(libs.gradle.compose.plugin)
  implementation(libs.gradle.compose.compiler.plugin)
  implementation(libs.gradle.ksp.plugin)
  implementation(libs.gradle.kotlinx.serialization.plugin)
}