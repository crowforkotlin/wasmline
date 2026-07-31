@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline

import crow.wasmline.gradle.BuildConfig
import crow.wasmline.gradle.extensions.WasmlineExtension
import crow.wasmline.gradle.tasks.DownloadWasmtimeTask
import crow.wasmline.gradle.tasks.WasmlineAssembleTask
import crow.wasmline.gradle.tasks.WasmlineServerDeployTask
import crow.wasmline.loader.internal.crypto.SignatureAlgorithmId
import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import crow.wasmline.plugin.core.download.WasmtimeDownloader
import crow.wasmline.plugin.core.util.PlatformDetector
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.internal.cc.base.logger
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.targets.js.KotlinWasmTargetType
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinWasmWasiTargetDsl
import org.jetbrains.kotlin.gradle.targets.js.ir.JsIrBinary
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.jvm.java

/**
 * Wasmline Gradle plugin — bridges the Kotlin IR compiler plugin and
 * registers build / deploy tasks for wasmline plugins.
 *
 * Registered tasks:
 * - `wasmlineAssembleDebug` — assemble with Development compilation output
 * - `wasmlineAssembleRelease` — assemble with Production compilation output
 * - `wasmlineServerDeploy` — start an HTTP server serving the assembled artifacts
 * - `wasmlineDownloadWasmtime` — download wasmtime binary
 * - `checkWasmlineToolchain` — verify wasmtime is available
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
 *         // Optional: defaults to ~/.wasmline/wasmtime (base directory).
 *         // The plugin searches for the executable in versioned subdirectories.
 *         directory = file(System.getenv("WASMTIME_MIN_HOME") ?: "$home/.wasmline/wasmtime")
 *         version = "latest" // or "v47.0.2"
 *         autoDownload = true
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

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> =
        kotlinCompilation.target.project.provider {
            listOf(
                SubpluginOption(
                    key = ENABLE_WASI_INIT_EXPORT_OPTION,
                    value = shouldEnableWasiInitExport(kotlinCompilation).toString(),
                ),
            )
        }

    override fun apply(target: Project) {
        super.apply(target)

        // 1. Create the DSL extension (available for all projects)
        val extension = target.extensions.create("wasmline", WasmlineExtension::class.java, target)

        // 2. Get Kotlin multiplatform extension if available
        val kotlinExtension = target.extensions.findByType(KotlinMultiplatformExtension::class.java)
          ?: return

        // 3. Register WASI-specific tasks ONLY for wasmWasi targets
        // Note: Use KotlinJsIrTarget as the base type, then filter by wasmTargetType
        logger.apply {
            kotlinExtension.targets.withType(KotlinJsIrTarget::class.java).configureEach { wasiTarget ->
                // Check if this is a WASI target based on wasmTargetType
                val isWasiTarget = wasiTarget.wasmTargetType == KotlinWasmTargetType.WASI

                if (isWasiTarget) {
                    // 3.1 Create wasmtime download and check tasks (once per WASI target)
                    createWasmlineDownloadTask(project = target, ext = extension)
                    createWasmtimeCheckTask(project = target, ext = extension)

                    // 3.2 Key-pair generation tasks (only for WASI)
                    createGenerateKeyPairTasks(project = target)

                    // 3.3 Build & deploy tasks (only for WASI)
                    registerAssembleTasks(project = target, ext = extension)
                    registerServerDeployTask(project = target, ext = extension)
                } else {
                    lifecycle("⚠️ Skipping wasm-js target: ${wasiTarget.name}")
                }
            }
        }
    }

    /**
     * Creates a `wasmlineDownloadWasmtime` task that users can run manually
     * to download the wasmtime toolchain before building.
     *
     * This is available regardless of autoDownload setting and provides
     * a convenient way to download wasmtime through Gradle.
     */
    private fun createWasmlineDownloadTask(project: Project, ext: WasmlineExtension) {
        project.tasks.register("wasmlineDownloadWasmtime", DownloadWasmtimeTask::class.java) { task ->
            task.group = "wasmline"
            task.description = "Download wasmtime binary for current platform"

            // Pass DSL configuration to the task
            task.wasmtimeDirectory.set(ext.wasmtime.directory)
            task.version.set(ext.wasmtime.version)
            task.platform.convention(detectCurrentPlatform())
            task.githubToken.set(ext.wasmtime.githubToken)

        }
    }

    // ==================== Wasmtime toolchain check ====================

    /**
     * Creates a `checkWasmlineToolchain` task that:
     * 1. Searches for wasmtime in the configured/default base directory
     *    (including versioned subdirectories)
     * 2. Optionally downloads wasmtime if autoDownload is enabled
     * 3. Provides helpful error messages with download instructions
     */
    private fun createWasmtimeCheckTask(project: Project, ext: WasmlineExtension) {
        project.tasks.register("checkWasmlineToolchain", DefaultTask::class.java) { task ->
            task.group = "wasmline"
            task.description = "Check and optionally download wasmtime toolchain"

            task.doLast {
                val baseDir = resolveWasmtimeBaseDirectory(project, ext)
                val platform = detectCurrentPlatform()
                project.logger.lifecycle("📍 Wasmtime base directory: ${baseDir.absolutePath}")

                // Search for executable (direct + versioned subdirectories)
                val executable = WasmtimeCompiler.findWasmtimeInDirectory(baseDir, platform)
                if (executable != null) {
                    project.logger.lifecycle("✅ Found wasmtime: ${executable.absolutePath}")
                    val versionOutput = runCommand(listOf(executable.absolutePath, "--version"))
                    project.logger.lifecycle("   Version: ${versionOutput.trim()}")
                    return@doLast
                }

                // Not found — handle missing wasmtime
                handleMissingWasmtime(project, ext, baseDir, platform)
            }
        }

        // Make all assemble tasks depend on the check task
        project.tasks.matching { it.name.startsWith("wasmlineAssemble") }.configureEach {
            it.dependsOn("checkWasmlineToolchain")
        }
    }

    /**
     * Resolve the base directory for wasmtime.
     *
     * Layer 1: DSL-configured directory
     * Layer 2: WASMTIME_ROOT environment variable
     * Layer 3: ~/.wasmline/wasmtime (default)
     */
    private fun resolveWasmtimeBaseDirectory(project: Project, ext: WasmlineExtension): File {
        ext.wasmtime.directory.orNull?.let { dir ->
            return dir.asFile
        }
        System.getenv("WASMTIME_ROOT")?.let { envPath ->
            project.logger.lifecycle("📍 Using WASMTIME_ROOT: $envPath")
            return File(envPath)
        }
        return File(System.getProperty("user.home"), ".wasmline/wasmtime")
    }

    /**
     * Handles missing wasmtime:
     * 1. If autoDownload is enabled, download through plugin-core
     * 2. After download, verify the executable exists
     * 3. If download fails or autoDownload is disabled, show instructions and throw
     */
    private fun handleMissingWasmtime(
        project: Project,
        ext: WasmlineExtension,
        baseDir: File,
        platform: String,
    ) {
        project.logger.error("❌ wasmtime not found in: ${baseDir.absolutePath}")
        project.logger.lifecycle("")

        // Show attempted locations
        val attemptedLocations = mutableListOf<String>()
        ext.wasmtime.directory.orNull?.let {
            attemptedLocations.add("Configured: ${it.asFile.absolutePath}")
        }
        System.getenv("WASMTIME_ROOT")?.let {
            attemptedLocations.add("Environment: $it")
        }
        attemptedLocations.add("Default: ~/.wasmline/wasmtime")

        project.logger.error("Attempted locations:")
        attemptedLocations.forEach { project.logger.error("  - $it") }
        project.logger.lifecycle("")

        // Attempt automatic download
        if (ext.wasmtime.autoDownload.get()) {
            project.logger.lifecycle("⚡ Attempting automatic download...")
            val version = ext.wasmtime.version.get()
            val success = attemptAutoDownload(project, ext, baseDir, platform, version)
            if (success) {
                // Re-verify after download
                val executable = WasmtimeCompiler.findWasmtimeInDirectory(baseDir, platform)
                if (executable != null) {
                    project.logger.lifecycle("✅ Automatic download successful!")
                    project.logger.lifecycle("   Location: ${executable.absolutePath}")
                    return
                }
                project.logger.warn("⚠️  Download appeared to succeed but executable not found.")
            } else {
                project.logger.warn("⚠️  Automatic download failed or unavailable.")
            }
            project.logger.lifecycle("")
        }

        // Provide comprehensive download instructions
        provideDownloadInstructions(project)

        // Add specific hint for sample projects
        if (project.rootProject.name != "wasmline") {
            project.logger.warn("")
            project.logger.warn("⚠️  NOTE: This appears to be a sample project.")
            project.logger.warn("   Please ensure you have downloaded wasmtime separately from:")
            project.logger.warn("   https://github.com/wasmtime/wasmtime/releases")
            project.logger.warn("")
        }

        throw GradleException(
            "wasmtime toolchain required but not found. " +
            "See error output above for download instructions."
        )
    }

    private fun attemptAutoDownload(
        project: Project,
        ext: WasmlineExtension,
        baseDir: File,
        platform: String,
        version: String,
    ): Boolean = try {
        project.logger.lifecycle("  Platform: $platform")
        project.logger.lifecycle("  Version: $version")
        project.logger.lifecycle("  Output: ${baseDir.absolutePath}")
        runBlocking {
            val downloader = WasmtimeDownloader()
            try {
                downloader.download(
                    githubToken = ext.wasmtime.githubToken.orNull,
                    version = version,
                    platform = platform,
                    outputDir = baseDir,
                )
            } finally {
                downloader.close()
            }
        }
        WasmtimeCompiler.findWasmtimeInDirectory(baseDir, platform) != null
    } catch (e: Exception) {
        project.logger.warn("Failed to download wasmtime: ${e.message}")
        false
    }

    /**
     * Provides comprehensive, user-friendly download instructions.
     */
    private fun provideDownloadInstructions(project: Project) {
        project.logger.lifecycle("💡 Download Wasmtime using one of these methods:")
        project.logger.lifecycle("")
        project.logger.lifecycle("Method 1: Using Gradle task (Recommended)")
        project.logger.lifecycle("  ./gradlew wasmlineDownloadWasmtime")
        project.logger.lifecycle("")
        project.logger.lifecycle("Method 2: Using standalone wasmline CLI")
        project.logger.lifecycle("  wasmline download -a x86_64-linux")
        project.logger.lifecycle("")
        project.logger.lifecycle("Method 3: Manual download from GitHub")
        project.logger.lifecycle("  https://github.com/crowforkotlin/wasmtime/releases")
        project.logger.lifecycle("")
        project.logger.lifecycle("Method 4: Set environment variable")
        project.logger.lifecycle("  export WASMTIME_ROOT=/path/to/wasmtime")
    }

    private fun detectCurrentPlatform(): String = PlatformDetector.detectPlatform()

    private fun runCommand(command: List<String>): String {
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output
        } catch (e: Exception) {
            ""
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

    private fun configureAssembleTask(task: WasmlineAssembleTask, project: Project, ext: WasmlineExtension, libraryDir: String) {
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
            project.layout.buildDirectory.dir("compileSync/wasmWasi/main/$libraryDir/optimized"),
        )

        // Output directory: build/wasmline/output/
        task.outputDir.set(
            project.layout.buildDirectory.dir("wasmline/output"),
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
                project.layout.buildDirectory.dir("wasmline/output/$pluginId-$version"),
            )
        }
    }

    // ==================== Existing helpers ====================

    private fun shouldEnableWasiInitExport(kotlinCompilation: KotlinCompilation<*>): Boolean =
        kotlinCompilation.target.platformType == KotlinPlatformType.wasm &&
            kotlinCompilation.defaultSourceSet.name == "wasmWasiMain"

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
