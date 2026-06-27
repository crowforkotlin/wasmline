@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline

import crow.wasmline.gradle.BuildConfig
import crow.wasmline.gradle.extensions.WasmlineExtension
import crow.wasmline.gradle.tasks.WasmlineAssembleTask
import crow.wasmline.gradle.tasks.WasmlineServerDeployTask
import crow.wasmline.loader.internal.crypto.SignatureAlgorithmId
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.slf4j.LoggerFactory
import kotlin.jvm.java

/**
 * Wasmline Gradle plugin — bridges the Kotlin IR compiler plugin and
 * registers build / deploy tasks for wasmline plugins.
 *
 * Registered tasks:
 * - `wasmlineAssembleDebug` — assemble with Development compilation output
 * - `wasmlineAssembleRelease` — assemble with Production compilation output
 * - `wasmlineServerDeploy` — start an HTTP server serving the assembled artifacts
 *
 * DSL usage (in the consuming project's `build.gradle.kts`):
 * ```kotlin
 * wasmline {
 *     manifest {
 *         pluginId = "crow.wasmline.demo"
 *         version = "1.0.0"
 *         signingKey = file("../keys/private.key").readText()
 *     }
 *     wasmtime {
 *         directory = file(System.getenv("WASMTIME_MIN_HOME") ?: "$home/.wasmline/wasmtime")
 *     }
 *     server {
 *         port = 8080
 *     }
 * }
 * ```
 *
 * 2026/6/5
 * @author crowforkotlin
 */
class WasmlinePlugin : KotlinCompilerPluginSupportPlugin {

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = BuildConfig.KOTLIN_PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = BuildConfig.KOTLIN_PLUGIN_GROUP,
        artifactId = BuildConfig.KOTLIN_PLUGIN_NAME,
        version = BuildConfig.KOTLIN_PLUGIN_VERSION,
    )

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        return kotlinCompilation.target.project.provider {
            listOf(
                SubpluginOption(
                    key = ENABLE_WASI_INIT_EXPORT_OPTION,
                    value = shouldEnableWasiInitExport(kotlinCompilation).toString(),
                ),
            )
        }
    }

    override fun apply(target: Project) {
        // 1. Register the DSL extension
        val extension = target.extensions.create("wasmline", WasmlineExtension::class.java, target)

        // 2. Existing key-pair generation tasks
        createGenerateKeyPairTasks(target)

        // 3. Register build & deploy tasks after the project has been evaluated
        //    so that DSL configuration values are available.
        target.afterEvaluate { project ->
            registerAssembleTasks(project, extension)
            registerServerDeployTask(project, extension)
        }
    }

    // ==================== Task registration ====================

    private fun registerAssembleTasks(project: Project, ext: WasmlineExtension) {
        // Debug variant — uses the Development Kotlin/WasmWasi compilation output.
        project.tasks.register("wasmlineAssembleDebug", WasmlineAssembleTask::class.java) { task ->
            task.description = "Assemble wasmline plugin (debug / Development variant)"
            task.buildVariant.set("Development")
            configureAssembleTask(task, project, ext, "developmentLibrary")
            task.dependsOn("compileDevelopmentLibraryKotlinWasmWasiOptimize")
        }

        // Release variant — uses the Production Kotlin/WasmWasi compilation output.
        project.tasks.register("wasmlineAssembleRelease", WasmlineAssembleTask::class.java) { task ->
            task.description = "Assemble wasmline plugin (release / Production variant)"
            task.buildVariant.set("Production")
            configureAssembleTask(task, project, ext, "productionLibrary")
            task.dependsOn("compileProductionLibraryKotlinWasmWasiOptimize")
        }
    }

    private fun configureAssembleTask(
        task: WasmlineAssembleTask,
        project: Project,
        ext: WasmlineExtension,
        libraryDir: String,
    ) {
        val manifestExt = ext.manifest

        // Manifest metadata
        task.pluginId.set(manifestExt.pluginId)
        task.pluginVersion.set(manifestExt.version)
        task.versionCode.set(manifestExt.versionCode)
        task.minSdkVersion.set(manifestExt.minSdkVersion)
        task.displayName.set(manifestExt.displayName)
        task.author.set(manifestExt.author)
        task.pluginDescription.set(manifestExt.description)
        task.iconUrl.set(manifestExt.iconUrl)
        task.homePageUrl.set(manifestExt.homePageUrl)
        task.signingKey.set(manifestExt.signingKey.map { it.asFile.readText().trim() })
        task.metadata.set(manifestExt.metadata)

        // Wasmtime configuration
        task.wasmtimeDirectory.set(ext.wasmtime.directory)
        task.compileTargets.set(ext.wasmtime.targets)

        // Compilation output directory — the .wasm file is resolved at task
        // execution time (after the Kotlin/WasmWasi compilation task has run).
        // Layout: build/compileSync/wasmWasi/main/{variant}Library/optimized/
        task.wasmCompileOutputDir.set(
            project.layout.buildDirectory.dir("compileSync/wasmWasi/main/$libraryDir/optimized")
        )

        // Output directory: build/wasmline/output/
        task.outputDir.set(
            project.layout.buildDirectory.dir("wasmline/output")
        )
    }

    private fun registerServerDeployTask(project: Project, ext: WasmlineExtension) {
        project.tasks.register("wasmlineServerDeploy", WasmlineServerDeployTask::class.java) { task ->
            task.port.set(ext.server.port)
            task.host.set(ext.server.host)

            // Determine which assemble variant to depend on.
            val variant = ext.serverDeployVariant.get().lowercase()
            val assembleTaskName = when (variant) {
                "release" -> "wasmlineAssembleRelease"
                else -> "wasmlineAssembleDebug"
            }
            task.dependsOn(assembleTaskName)

            // Serve directory: build/wasmline/output/{pluginId}-{version}/
            val pluginId = ext.manifest.pluginId.orNull ?: "unknown"
            val version = ext.manifest.version.getOrElse("1.0.0")
            task.serveDirectory.set(
                project.layout.buildDirectory.dir("wasmline/output/$pluginId-$version")
            )
        }
    }

    // ==================== Existing helpers ====================

    private fun shouldEnableWasiInitExport(kotlinCompilation: KotlinCompilation<*>): Boolean {
        return kotlinCompilation.target.platformType == KotlinPlatformType.wasm &&
                kotlinCompilation.defaultSourceSet.name == "wasmWasiMain"
    }

    private fun createGenerateKeyPairTasks(project: Project) {
        project.tasks.register("generateWasmlineManifestKeyPairEd25519") { task ->
            task.group = "wasmline"
            task.doLast {
                generateKeyPair(SignatureAlgorithmId.Ed25519)
            }
        }
        project.tasks.register("generateWasmlineManifestKeyPairEcdsaP256") { task ->
            task.group = "wasmline"
            task.doLast {
                generateKeyPair(SignatureAlgorithmId.EcdsaP256)
            }
        }
    }

    private companion object {
        const val ENABLE_WASI_INIT_EXPORT_OPTION = "enableWasiInitExport"
    }

    @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER") // Access :zipline-loader internals.
    private fun generateKeyPair(algorithm: SignatureAlgorithmId) {
        val logger = LoggerFactory.getLogger(WasmlinePlugin::class.java)
        val keyPair = crow.wasmline.loader.internal.crypto.generateKeyPair(algorithm)
        logger.warn("---------------- ----------------------------------------------------------------")
        logger.warn("    ALGORITHM: $algorithm")
        logger.warn("    PUBLIC KEY: ${keyPair.publicKey.hex()}")
        logger.warn("    PRIVATE KEY: ${keyPair.privateKey.hex()}")
        logger.warn("---------------- ----------------------------------------------------------------")
    }
}