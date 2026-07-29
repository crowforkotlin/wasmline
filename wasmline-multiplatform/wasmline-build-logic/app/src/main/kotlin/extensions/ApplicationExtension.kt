@file:OptIn(ExperimentalWasmDsl::class, ExperimentalKotlinGradlePluginApi::class)

package extensions

import buildlogic.Config
import buildlogic.applyBaseHierarchyTemplate
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.desktop.DesktopExtension
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsTargetDsl
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinWasmJsTargetDsl

abstract class ApplicationExtension(val project: Project) {

    /**
     * Config
     *
     * @param versionCode version code
     * @param versionName version name
     * @param enableJs whether to enable WebJs
     * @param enableWasmJs whether to enable WebWasmJs
     * @param desktopMainClass main class package path
     * @param jsModuleName JS module name
     * @param jsOutputFileName JS output file name
     * @param desktopConfig desktop configuration, see [DesktopExtension]
     * @param wasmJsConfig WasmJS configuration, see [KotlinWasmJsTargetDsl]
     * @param jsConfig WasmJS configuration, see [KotlinJsTargetDsl]
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
