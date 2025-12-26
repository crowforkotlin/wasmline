package extensions

import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.DependencyHandlerScope
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.desktop.DesktopExtension

abstract class AndroidExtension(val project: Project) {

  fun config(
    versionCode: Int,
    versionName: String,
    namespace: String = Config.getNamespace(project),
    applicationId: String = Config.ApplicationId,
    outputFileName: String? = null,
    dependencyHandlerScope: DependencyHandlerScope.() -> Unit = {}
  ) {
    project.configure<BaseAppModuleExtension> {
      this.namespace = namespace
      defaultConfig {
        this.versionCode = versionCode
        this.versionName = versionName
        this.applicationId = applicationId
      }
      this.applicationVariants.all {
        this.outputs.all {
          this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
          outputFileName?.let {
            this.outputFileName = "app_" + defaultConfig.versionName  + "_" + name + ".apk"
          }
        }
      }
      project.dependencies(dependencyHandlerScope)
    }
  }
}