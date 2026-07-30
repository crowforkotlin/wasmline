@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline

import crow.wasmline.gradle.BuildConfig
import crow.wasmline.gradle.extensions.WasmlineExtension
import crow.wasmline.gradle.internal.WasmtimeCompiler
import crow.wasmline.gradle.tasks.DownloadWasmtimeTask
import crow.wasmline.gradle.tasks.WasmlineAssembleTask
import crow.wasmline.gradle.tasks.WasmlineServerDeployTask
import crow.wasmline.loader.internal.crypto.SignatureAlgorithmId
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
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
        // 1. Register the DSL extension
        val extension = target.extensions.create("wasmline", WasmlineExtension::class.java, target)

        // 2. Create convenience download task (available even without autoDownload)
        target.createWasmlineDownloadTask(extension)

        // 3. Create wasmtime toolchain check task
        createWasmtimeCheckTask(target, extension)

        // 4. Existing key-pair generation tasks
        createGenerateKeyPairTasks(target)

        // 5. Register build & deploy tasks after the project has been evaluated
        //    so that DSL configuration values are available.
        target.afterEvaluate { project ->
            registerAssembleTasks(project, extension)
            registerServerDeployTask(project, extension)
        }
    }

    /**
     * Creates a `wasmlineDownloadWasmtime` task that users can run manually
     * to download the wasmtime toolchain before building.
     * 
     * This is available regardless of autoDownload setting and provides
     * a convenient way to download wasmtime through Gradle.
     */
    private fun Project.createWasmlineDownloadTask(ext: WasmlineExtension) {
        tasks.register("wasmlineDownloadWasmtime", DownloadWasmtimeTask::class.java) { task ->
            task.group = "wasmline"
            task.description = "Download wasmtime binary for current platform"
            
            // Pass DSL configuration to the task
            task.wasmtimeDirectory.set(ext.wasmtime.directory)
            task.version.set(ext.wasmtime.version)
            task.platform.convention(detectCurrentPlatform())
        }
        
        logger.lifecycle("✅ Registered task: wasmlineDownloadWasmtime")
        logger.lifecycle("   Usage: ./gradlew wasmlineDownloadWasmtime")
    }

    /**
     * Creates a `checkWasmlineToolchain` task that:
     * 1. Checks if wasmtime is available at configured directory
     * 2. Falls back to default paths if not configured
     * 3. Optionally downloads wasmtime if autoDownload is enabled
     * 4. Provides helpful error messages with download instructions
     */
    private fun createWasmtimeCheckTask(project: Project, ext: WasmlineExtension) {
        project.tasks.register("checkWasmlineToolchain", DefaultTask::class.java) { task ->
            task.group = "wasmline"
            task.description = "Check and optionally download wasmtime toolchain"

            task.doLast {
                try {
                    val wasmtimeDir = resolveWasmtimeDirectory(project, ext)
                    
                    // Check if executable exists
                    val executable = WasmtimeCompiler.resolveExecutable(wasmtimeDir)
                    project.logger.lifecycle("✅ Found wasmtime: ${executable.absolutePath}")
                    
                    val versionOutput = runCommand(listOf(executable.absolutePath, "--version"))
                    project.logger.lifecycle("   Version: ${versionOutput.trim()}")
                    
                } catch (e: GradleException) {
                    handleMissingWasmtime(project, ext, e)
                }
            }
        }

        // Make all assemble tasks depend on the check task
        project.tasks.matching { it.name.startsWith("wasmlineAssemble") }.configureEach {
            it.dependsOn("checkWasmlineToolchain")
        }
    }

    /**
     * Multi-layer fallback strategy for locating wasmtime:
     * Layer 1: Explicitly configured directory
     * Layer 2: Environment variable WASMTIME_ROOT
     * Layer 3: Default path ~/.wasmline/wasmtime
     */
    private fun resolveWasmtimeDirectory(project: Project, ext: WasmlineExtension): File {
        // Layer 1: DSL configuration
        ext.wasmtime.directory.orNull?.let { dir ->
            val file = dir.asFile
            project.logger.lifecycle("📍 Using configured wasmtime directory: ${file.absolutePath}")
            return file
        }

        // Layer 2: Environment variable
        System.getenv("WASMTIME_ROOT")?.let { envPath ->
            val file = File(envPath)
            project.logger.lifecycle("📍 Using WASMTIME_ROOT environment variable: $envPath")
            return file
        }

        // Layer 3: Default path
        val homeDir = System.getProperty("user.home")
        val defaultPath = File(homeDir, ".wasmline/wasmtime")
        project.logger.lifecycle("📍 Using default wasmtime directory: ${defaultPath.absolutePath}")
        return defaultPath
    }

    /**
     * Handles missing wasmtime with intelligent fallback strategies:
     * 1. Try automatic download via CLI (if available and enabled)
     * 2. Provide detailed manual download instructions
     */
    private fun handleMissingWasmtime(project: Project, ext: WasmlineExtension, originalError: GradleException) {
        project.logger.error("❌ wasmtime not found!")
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
            val success = attemptAutoDownload(project, ext)
            if (success) {
                project.logger.lifecycle("✅ Automatic download successful! Build will retry.")
                return
            }
            project.logger.warn("⚠️  Automatic download failed or unavailable.")
            project.logger.lifecycle("")
        }

        // Provide comprehensive download instructions
        provideDownloadInstructions(project)
        
        throw GradleException(
            "wasmtime toolchain required but not found. " +
            "See error output above for download instructions."
        )
    }

    /**
     * Attempts to download wasmtime using accessible CLI tools.
     * Tries multiple approaches in order of preference.
     */
    private fun attemptAutoDownload(project: Project, ext: WasmlineExtension): Boolean {
        val version = ext.wasmtime.version.get()
        val platform = detectCurrentPlatform()
        val outputDir = File(project.buildDir, "wasmline/wasmtime")
        
        project.logger.lifecycle("  Platform: $platform")
        project.logger.lifecycle("  Version: $version")
        project.logger.lifecycle("  Output: ${outputDir.absolutePath}")
        project.logger.lifecycle("")

        // Approach 1: Use embedded CLI JAR (only works in wasmline project)
        project.findProject(":wasmline-cli")?.tasks?.findByName("jar")?.outputs?.files?.firstOrNull()?.let { cliJar ->
            project.logger.lifecycle("Using wasmline-cli from project...")
            return tryRunCliDownload(project, cliJar, platform, version, outputDir)
        }

        // Approach 2: Check for globally installed wasmline CLI
        project.logger.lifecycle("Checking for global wasmline CLI...")
        if (tryGlobalCliDownload(project, platform, version, outputDir)) {
            return true
        }

        // Approach 3: Provide fallback instructions
        project.logger.warn("No download method available.")
        return false
    }

    private fun tryRunCliDownload(
        project: Project,
        cliJar: File,
        platform: String,
        version: String,
        outputDir: File
    ): Boolean {
        return try {
            val args = listOf(
                "java", "-jar", cliJar.absolutePath, "download",
                "-a", platform,
                "-v", version,
                "-o", outputDir.absolutePath
            )
            
            project.logger.lifecycle("Executing: ${args.joinToString(" ")}")
            val process = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    if (line.contains("Downloading")) {
                        project.logger.lifecycle("  📥 $line")
                    } else if (line.contains("Success") || line.contains("Skipping")) {
                        project.logger.lifecycle("  ✅ $line")
                    } else if (line.isNotEmpty()) {
                        project.logger.lifecycle("    $line")
                    }
                }
            }
            
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            project.logger.warn("Failed to run wasmline-cli: ${e.message}")
            false
        }
    }

    private fun tryGlobalCliDownload(
        project: Project,
        platform: String,
        version: String,
        outputDir: File
    ): Boolean {
        return try {
            val args = listOf("wasmline", "download", "-a", platform, "-v", version, "-o", outputDir.absolutePath)
            val process = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    if (line.isNotEmpty()) {
                        project.logger.lifecycle("  $line")
                    }
                }
            }
            
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: IOException) {
            // wasmline CLI not found in PATH
            false
        }
    }

    /**
     * Provides comprehensive, user-friendly download instructions.
     * Covers all supported platforms and installation methods.
     */
    private fun provideDownloadInstructions(project: Project) {
        project.logger.lifecycle("💡 Download Wasmtime using one of these methods:")
        project.logger.lifecycle("")
        project.logger.lifecycle("Method 1: Using wasmline CLI (Recommended)")
        project.logger.lifecycle("  # Install first if needed:")
        project.logger.lifecycle("  pip install wasmline-cli  # or download from releases")
        project.logger.lifecycle("")
        project.logger.lifecycle("  # Then download:")
        project.logger.lifecycle("  wasmline download -a x86_64-linux")
        project.logger.lifecycle("  wasmline download -a aarch64-macos")
        project.logger.lifecycle("  wasmline download -a x86_64-windows")
        project.logger.lifecycle("")
        
        project.logger.lifecycle("Method 2: Using project's built-in CLI")
        project.logger.lifecycle("  # If building wasmline from source:")
        project.logger.lifecycle("  ./gradlew :wasmline-cli:run --args=\"download -a <platform>\"")
        project.logger.lifecycle("")
        
        project.logger.lifecycle("Method 3: Manual download from GitHub")
        project.logger.lifecycle("  # Go to: https://github.com/crowforkotlin/wasmtime/releases")
        project.logger.lifecycle("  # Download: wasmtime-v<VERSION>-<ARCH>-min.tar.xz")
        project.logger.lifecycle("  # Extract to desired location")
        project.logger.lifecycle("")
        
        project.logger.lifecycle("Method 4: Set environment variable")
        project.logger.lifecycle("  export WASMTIME_ROOT=/path/to/wasmtime-v<VERSION>-<ARCH>-min")
        project.logger.lifecycle("")
        
        project.logger.lifecycle("🔗 Quick links:")
        project.logger.lifecycle("  • Releases: https://github.com/crowforkotlin/wasmtime/releases")
        project.logger.lifecycle("  • Docs: https://wasmline.dev/installation")
        project.logger.lifecycle("")
    }

    /**
     * Detects current OS and architecture for wasmtime download.
     * Mirrors the logic in Download.kt for consistency.
     */
    private fun detectCurrentPlatform(): String {
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        
        val normalizedOs = when {
            osName.contains("win") -> "windows"
            osName.contains("mac") -> "macos"
            osName.contains("linux") -> "linux"
            else -> "unknown"
        }
        
        val normalizedArch = when {
            osArch.contains("amd64") || osArch.contains("x86_64") -> "x86_64"
            osArch.contains("aarch64") || osArch.contains("arm64") -> "aarch64"
            else -> osArch
        }
        
        return "$normalizedArch-$normalizedOs"
    }

    /**
     * Executes a command and returns its output.
     */
    private fun runCommand(command: List<String>): String {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return output
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
