package extensions

import Config
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.AppExtension
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.DependencyHandlerScope
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

abstract class AndroidExtension(val project: Project) {

  fun config(
    versionCode: Int,
    versionName: String,
    namespace: String = Config.getNamespace(project),
    applicationId: String = Config.ApplicationId,
    outputFileName: String? = null,
    dependencyHandlerScope: DependencyHandlerScope.() -> Unit = {}
  ) {
    project.configure<ApplicationExtension> {
      this.namespace = namespace
      defaultConfig {
        this.versionCode = versionCode
        this.versionName = versionName
        this.applicationId = applicationId
      }
      if (outputFileName != null) {
        val androidExtension = project.extensions.findByName("android")
        try {
          val appExt = androidExtension as? com.android.build.gradle.AppExtension
          appExt?.applicationVariants?.all {
            outputs.all {
              val output = this as? com.android.build.gradle.internal.api.ApkVariantOutputImpl
              output?.outputFileName = "app_${versionName}_${this.name}.apk"
            }
          }
        } catch (e: Exception) {
          project.logger.warn("无法修改 APK 文件名: ${e.message}")
        }
      }
      project.dependencies(dependencyHandlerScope)
    }
  }
}