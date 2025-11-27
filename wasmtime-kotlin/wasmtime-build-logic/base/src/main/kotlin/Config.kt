@file:Suppress("SpellCheckingInspection", "ConstPropertyName")

import org.gradle.api.Project


object Config {

  const val Group = "crow.wasmtime"
  const val ApplicationId = "${Group}.wasmline"
  const val ApplicationName = "WasmLine"

  /**
   * mordecaix-test/
   * ├── android-compose/
   * └── android/
   * getBaseName ... => TestCompose、TestAndroid
   *
   * time: 2025-09-12 11:50:03 上午 星期五
   * @author:crow
   */
  fun getBaseName(project: Project): String {
    var baseName = ""
    var p: Project? = project
    while (p != null && p != project.rootProject) {
      baseName = p.name.substringAfterLast("-").replaceFirstChar { it.uppercaseChar() } + baseName
      p = p.parent
    }
    return baseName
  }

  /**
   * mordecaix-test/
   * ├── android-compose/
   * └── android/
   * 取模块分割'-'后的最后一个昵称
   *
   * getNamespace... => $GROUP$namespace => com.mordecaix.test.compose、com.mordecaix.test.android
   *
   * time: 2025-09-12 11:50:11 上午 星期五
   * @author:crow
   */
  fun getNamespace(project: Project): String {
    var namespace = ""
    var p: Project? = project
    while (p != null && p != project.rootProject) {
      namespace = ".${p.name.substringAfterLast("-")}$namespace"
      p = p.parent
    }
    return "$Group$namespace"
  }
}