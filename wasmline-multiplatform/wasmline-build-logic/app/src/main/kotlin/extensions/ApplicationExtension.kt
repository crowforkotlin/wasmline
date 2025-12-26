@file:OptIn(ExperimentalWasmDsl::class, ExperimentalKotlinGradlePluginApi::class)

package extensions

import applyBaseHierarchyTemplate
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import libsEx
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getValue
import org.gradle.kotlin.dsl.getting
import org.gradle.kotlin.dsl.invoke
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.desktop.DesktopExtension
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyBuilder
import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsTargetDsl
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinWasmJsTargetDsl
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinWasmTargetDsl

abstract class ApplicationExtension(val project: Project) {

  /**
   * Config
   *
   * @param versionCode 版本号
   * @param versionName 版本名称
   * @param enableJs 是否启用WebJs
   * @param enableWasmJs 是否启用WebWasmJs
   * @param desktopMainClass 主程序类包路径
   * @param jsModuleName JS模块名称
   * @param jsOutputFileName JS文件产物名称
   * @param desktopConfig 桌面端配置 详情看 [DesktopExtension]
   * @param wasmJsConfig WasmJS配置 详情看 [KotlinWasmJsTargetDsl]
   * @param jsConfig  WasmJS配置 详情看 [KotlinJsTargetDsl]
   * @receiver
   * @receiver
   * @receiver
   */
  fun config(
    versionCode: Int,
    versionName: String,
    enableJs: Boolean = false,
    enableWasmJs: Boolean = false,
    desktopMainClass: String? = null,
    jsModuleName: String = "composeApp",
    jsOutputFileName: String = "composeApp.js",
    desktopConfig: DesktopExtension.() -> Unit = {},
    wasmJsConfig: KotlinWasmJsTargetDsl.() -> Unit = {},
    jsConfig: KotlinJsTargetDsl.() -> Unit = {},
  ) {
    project.configure<KotlinMultiplatformExtension> {
      this.applyBaseHierarchyTemplate()
      if (enableJs) {
        this.js {
          this.jsConfig()
          this.outputModuleName.set(jsModuleName)
          this.browser { this.commonWebpackConfig { this.outputFileName = jsOutputFileName } }
        }
      }
      if (enableWasmJs) {
        this.wasmJs {
          project.group = Config.Group
          this.wasmJsConfig()
          this.outputModuleName.set(jsModuleName)
          this.browser { this.commonWebpackConfig { this.outputFileName = jsOutputFileName } }
        }
      }
    }
    project.configure<BaseAppModuleExtension> {
      defaultConfig {
        this.versionCode = versionCode
        this.versionName = versionName
      }
    }
    project.configure<ComposeExtension> {
      extensions.configure<DesktopExtension> {
        this.desktopConfig()
        application {
          mainClass = desktopMainClass
          nativeDistributions {
            packageVersion = versionName
          }
        }
      }
    }
  }


}