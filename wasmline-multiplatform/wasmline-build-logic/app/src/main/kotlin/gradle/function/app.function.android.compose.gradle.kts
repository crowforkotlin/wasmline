import buildlogic.libsEx
import gradle.kotlin.dsl.accessors._fb079f171776054018bb93a43cbfc29b.composeCompiler

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
}

composeCompiler {
  // 输出 compose 稳定性报告，执行 outputCompilerReports 任务
  // https://developer.android.com/jetpack/compose/performance/stability/diagnose#compose-compiler
  reportsDestination.set(
    layout.buildDirectory.get().asFile.resolve("compose_compiler")
  )

  // 对 Compose 配置外部类的稳定性
  // 只允许配置已有第三方库里面的类，如果是自己的类请打上 @Stable 注解
  // 配置规则可以查看 https://android-review.googlesource.com/c/platform/frameworks/support/+/2668595
  stabilityConfigurationFiles.set(
    listOf(
      layout.projectDirectory.file(rootDir.resolve("config/compose-stability-config.txt").absolutePath)
    )
  )
}

android {
  dependencies {

    // 相当于stdlib 必不可少的
    implementation(libsEx.`androidx-compose-runtime`)
    implementation(libsEx.`androidx-compose-foundation`)
    implementation(libsEx.`androidx-compose-ui`)
    implementation(libsEx.`androidx-compose-ui-graphics`)

    debugImplementation(libsEx.`androidx-compose-ui-tooling`)
    implementation(libsEx.`androidx-compose-ui-tooling-preview`)
  }
}
