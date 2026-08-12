@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline

import crow.wasmline.gradle.BuildConfig
import crow.wasmline.gradle.extensions.WasmlineExtension
import crow.wasmline.gradle.tasks.DownloadComponentToolsTask
import crow.wasmline.gradle.tasks.DownloadWasmtimeTask
import crow.wasmline.gradle.tasks.WasmlineAssembleTask
import crow.wasmline.gradle.tasks.WasmlineComponentAotTask
import crow.wasmline.gradle.tasks.WasmlineComponentizeTask
import crow.wasmline.gradle.tasks.WasmlineGenerateHostWitBindingsTask
import crow.wasmline.gradle.tasks.WasmlineGenerateWitBindingsTask
import crow.wasmline.gradle.tasks.WasmlineServerDeployTask
import crow.wasmline.loader.internal.crypto.SignatureAlgorithmId
import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import crow.wasmline.plugin.core.component.ComponentBuildRecords
import crow.wasmline.plugin.core.download.WasmtimeDistribution
import crow.wasmline.plugin.core.download.WasmtimeDownloader
import crow.wasmline.plugin.core.util.PlatformDetector
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.targets.js.KotlinWasmTargetType
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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
            val extension = kotlinCompilation.target.project.extensions.getByType(WasmlineExtension::class.java)
            val guestTransport = resolveGuestTransport(
                compilation = kotlinCompilation,
                executionModel = extension.manifest.executionModel.get(),
                invocationProtocol = extension.manifest.invocationProtocol.get(),
            )
            listOf(
                SubpluginOption(
                    key = GUEST_TRANSPORT_OPTION,
                    value = guestTransport,
                ),
            )
        }

    override fun apply(target: Project) {
        super.apply(target)

        // 1. Create the DSL extension (available for all projects)
        val extension = target.extensions.create("wasmline", WasmlineExtension::class.java, target)

        // 2. Get Kotlin multiplatform extension if available
        val kotlinExtension = target.extensions.findByType(KotlinMultiplatformExtension::class.java)
        val kotlinJvmExtension = target.extensions.findByType(KotlinJvmProjectExtension::class.java)

        target.afterEvaluate {
            configureHostBindings(target, extension, kotlinExtension, kotlinJvmExtension)
        }
        if (kotlinExtension == null) return

        val wasiTasksRegistered = AtomicBoolean(false)
        target.afterEvaluate {
            if (wasiTasksRegistered.get()) {
                configureAssembleTaskGraph(project = target, ext = extension)
            }
        }

        // Register Wasmtime tasks only once, even when a project declares multiple WASI targets.
        kotlinExtension.targets.withType(KotlinJsIrTarget::class.java).configureEach { wasiTarget ->
            if (wasiTarget.wasmTargetType == KotlinWasmTargetType.WASI && wasiTasksRegistered.compareAndSet(false, true)) {
                createWasmlineDownloadTask(project = target, ext = extension)
                createComponentDownloadTask(project = target, ext = extension)
                createWasmtimeCheckTask(project = target, ext = extension)
                createGenerateKeyPairTasks(project = target)
                registerAssembleTasks(project = target, ext = extension)
                registerServerDeployTask(project = target, ext = extension)
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
            task.distribution.set(WasmtimeDistribution.MINIMAL)
            task.githubToken.set(ext.wasmtime.githubToken)
        }
        project.tasks.register("wasmlineDownloadWasmtimeCompiler", DownloadWasmtimeTask::class.java) { task ->
            task.group = "wasmline"
            task.description = "Download the full Wasmtime CLI used for Component AOT compilation"
            task.wasmtimeDirectory.set(ext.wasmtime.compilerDirectory)
            task.version.set(ext.wasmtime.compilerVersion)
            task.platform.convention(detectCurrentPlatform())
            task.distribution.set(WasmtimeDistribution.FULL)
            task.githubToken.set(ext.wasmtime.githubToken)
            task.installedExecutable.set(
                ext.wasmtime.compilerDirectory.file(fullWasmtimeExecutableName()),
            )
        }
    }

    private fun createComponentDownloadTask(project: Project, ext: WasmlineExtension) {
        project.tasks.register("wasmlineDownloadComponentTools", DownloadComponentToolsTask::class.java) { task ->
            task.group = "wasmline"
            task.description = "Download wit-bindgen, wasm-tools and the WASI Preview 1 adapter"
            task.toolCacheDirectory.set(ext.component.toolCacheDirectory)
            task.witBindgenVersion.set(ext.component.witBindgenVersion)
            task.wasmToolsVersion.set(ext.component.wasmToolsVersion)
            task.platform.convention(detectCurrentPlatform())
            task.force.convention(false)
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
            task.onlyIf {
                ext.manifest.executionModel.get() == WasmlineExecutionModel.CORE_WASM
            }

            task.doLast {
                val baseDir = resolveWasmtimeBaseDirectory(project, ext)
                val platform = detectCurrentPlatform()
                project.logger.info("Wasmtime base directory: ${baseDir.absolutePath}")

                // Search for executable (direct + versioned subdirectories)
                val executable = WasmtimeCompiler.findWasmtimeInDirectory(
                    baseDir = baseDir,
                    platform = platform,
                    version = ext.wasmtime.version.get(),
                )
                if (executable != null) {
                    project.logger.info("Found wasmtime: ${executable.absolutePath}")
                    val versionOutput = runCommand(listOf(executable.absolutePath, "--version"))
                    project.logger.info("Wasmtime version: ${versionOutput.trim()}")
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
            project.logger.info("Using WASMTIME_ROOT: $envPath")
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
    private fun handleMissingWasmtime(project: Project, ext: WasmlineExtension, baseDir: File, platform: String) {
        project.logger.error("Wasmtime ${ext.wasmtime.version.get()} was not found in: ${baseDir.absolutePath}")

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

        // Attempt automatic download
        if (ext.wasmtime.autoDownload.get()) {
            project.logger.lifecycle("Attempting automatic Wasmtime download")
            val version = ext.wasmtime.version.get()
            val success = attemptAutoDownload(project, ext, baseDir, platform, version)
            if (success) {
                // Re-verify after download
                val executable = WasmtimeCompiler.findWasmtimeInDirectory(
                    baseDir = baseDir,
                    platform = platform,
                    version = version,
                )
                if (executable != null) {
                    project.logger.lifecycle("Wasmtime download completed: ${executable.absolutePath}")
                    return
                }
                project.logger.warn("Wasmtime download completed but the executable was not found.")
            } else {
                project.logger.warn("Automatic Wasmtime download failed or is unavailable.")
            }
        }

        // Provide comprehensive download instructions
        provideDownloadInstructions(project)

        // Add specific hint for sample projects
        if (project.rootProject.name != "wasmline") {
            project.logger.warn("This appears to be a sample project.")
            project.logger.warn("   Please ensure you have downloaded wasmtime separately from:")
            project.logger.warn("   https://github.com/wasmtime/wasmtime/releases")
        }

        throw GradleException(
            "wasmtime toolchain required but not found. " +
                "See error output above for download instructions.",
        )
    }

    private fun attemptAutoDownload(project: Project, ext: WasmlineExtension, baseDir: File, platform: String, version: String): Boolean =
        try {
            project.logger.info("Wasmtime platform: $platform")
            project.logger.info("Wasmtime version: $version")
            project.logger.info("Wasmtime output: ${baseDir.absolutePath}")
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
            WasmtimeCompiler.findWasmtimeInDirectory(
                baseDir = baseDir,
                platform = platform,
                version = version,
            ) != null
        } catch (e: Exception) {
            project.logger.warn("Failed to download wasmtime: ${e.message}")
            false
        }

    /**
     * Provides comprehensive, user-friendly download instructions.
     */
    private fun provideDownloadInstructions(project: Project) {
        project.logger.error("Download Wasmtime using one of these methods:")
        project.logger.lifecycle("Method 1: Using Gradle task (Recommended)")
        project.logger.lifecycle("  ./gradlew wasmlineDownloadWasmtime")
        project.logger.lifecycle("Method 2: Using standalone wasmline CLI")
        project.logger.lifecycle("  wasmline download -a x86_64-linux")
        project.logger.lifecycle("Method 3: Manual download from GitHub")
        project.logger.lifecycle("  https://github.com/crowforkotlin/wasmtime/releases")
        project.logger.lifecycle("Method 4: Set environment variable")
        project.logger.lifecycle("  export WASMTIME_ROOT=/path/to/wasmtime")
    }

    private fun detectCurrentPlatform(): String = PlatformDetector.detectPlatform()

    private fun fullWasmtimeExecutableName(): String =
        if (System.getProperty("os.name").lowercase().contains("win")) "wasmtime.exe" else "wasmtime"

    private fun runCommand(command: List<String>): String = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output
    } catch (e: Exception) {
        ""
    }

    // ==================== Task registration ====================

    private fun registerAssembleTasks(project: Project, ext: WasmlineExtension) {
        val debugCompileTask = "compileDevelopmentLibraryKotlinWasmWasiOptimize"
        val releaseCompileTask = "compileProductionLibraryKotlinWasmWasiOptimize"

        val debugComponent = registerComponentizeTask(
            project = project,
            ext = ext,
            taskName = "wasmlineComponentizeDebug",
            variantName = "debug",
        )
        val releaseComponent = registerComponentizeTask(
            project = project,
            ext = ext,
            taskName = "wasmlineComponentizeRelease",
            variantName = "release",
        )
        registerComponentAotTask(
            project = project,
            ext = ext,
            componentTask = debugComponent,
            taskName = "wasmlineComponentAotDebug",
            variantName = "debug",
        )
        registerComponentAotTask(
            project = project,
            ext = ext,
            componentTask = releaseComponent,
            taskName = "wasmlineComponentAotRelease",
            variantName = "release",
        )

        project.tasks.register("wasmlineAssembleDebug", WasmlineAssembleTask::class.java) { task ->
            task.description = "Assemble wasmline plugin (debug / Development variant)"
            task.buildVariant.set("Development")
            configureAssembleTask(task, project, ext)
        }

        project.tasks.register("wasmlineAssembleRelease", WasmlineAssembleTask::class.java) { task ->
            task.description = "Assemble wasmline plugin (release / Production variant)"
            task.buildVariant.set("Production")
            configureAssembleTask(task, project, ext)
        }
    }

    private fun registerGenerateBindingsTask(project: Project, ext: WasmlineExtension) = project.tasks.register(
        "wasmlineGenerateWitBindings",
        WasmlineGenerateWitBindingsTask::class.java,
    ) { task ->
        task.group = "wasmline"
        task.description = "Generate Kotlin Component bindings from WIT"
        val componentService = ext.manifest.executionModel.get() == WasmlineExecutionModel.COMPONENT_MODEL &&
            ext.manifest.invocationProtocol.get() == WasmlineInvocationProtocol.WASMLINE_SERVICE
        if (!componentService) {
            task.witDirectory.set(ext.component.witDirectory)
        }
        task.outputDirectory.set(
            ext.manifest.executionModel.zip(ext.manifest.invocationProtocol) { model, protocol ->
                model == WasmlineExecutionModel.COMPONENT_MODEL &&
                    protocol == WasmlineInvocationProtocol.WASMLINE_SERVICE
            }.flatMap { isComponentService ->
                if (isComponentService) {
                    project.layout.buildDirectory.dir("generated/wasmline/component-service")
                } else {
                    ext.component.generatedSourcesDirectory
                }
            },
        )
        task.world.set(ext.component.world)
        task.kotlinImports.set(ext.component.kotlinImports)
        task.invocationProtocol.set(ext.manifest.invocationProtocol)
        task.executionModel.set(ext.manifest.executionModel)
        task.witBindgenVersion.set(ext.component.witBindgenVersion)
        task.platform.convention(detectCurrentPlatform())
        task.autoDownload.set(ext.component.autoDownload)
        task.witBindgenExecutable.set(ext.component.witBindgenExecutable)
        task.toolCacheDirectory.set(ext.component.toolCacheDirectory)
        task.githubToken.set(ext.wasmtime.githubToken)
    }

    private fun configureHostBindings(
        project: Project,
        ext: WasmlineExtension,
        kotlinExtension: KotlinMultiplatformExtension?,
        kotlinJvmExtension: KotlinJvmProjectExtension?,
    ) {
        if (!ext.component.hostBindingsEnabled.get()) return
        val task = project.tasks.register(
            "wasmlineGenerateHostWitBindings",
            WasmlineGenerateHostWitBindingsTask::class.java,
        ) { generation ->
            generation.group = "wasmline"
            generation.description = "Generate Kotlin Host facades from the configured WIT world"
            generation.witDirectory.set(ext.component.witDirectory)
            generation.outputDirectory.set(ext.component.hostGeneratedSourcesDirectory)
            generation.world.set(ext.component.world)
            generation.kotlinPackage.set(ext.component.hostKotlinPackage)
            generation.resourceSupport.set(ext.component.hostResourceSupport)
        }
        if (kotlinExtension != null) {
            val sourceSetName = ext.component.hostSourceSet.get()
            val sourceSet = kotlinExtension.sourceSets.findByName(sourceSetName)
                ?: throw GradleException(
                    "Wasmline Host WIT source set '$sourceSetName' does not exist. " +
                        "Set component.hostSourceSet to a Host Kotlin source set.",
                )
            sourceSet.kotlin.srcDir(task.flatMap { it.outputDirectory })
            project.tasks.matching { compilationTask ->
                compilationTask.name.startsWith("compile", ignoreCase = true) &&
                    compilationTask.name.contains(sourceSetName.removeSuffix("Main"), ignoreCase = true)
            }.configureEach { it.dependsOn(task) }
        } else if (kotlinJvmExtension != null) {
            kotlinJvmExtension.sourceSets.getByName("main").kotlin.srcDir(task.flatMap { it.outputDirectory })
            project.tasks.matching { it.name == "compileKotlin" }.configureEach { it.dependsOn(task) }
        } else {
            throw GradleException("Wasmline Host WIT generation requires a Kotlin JVM or Kotlin Multiplatform project.")
        }
    }

    internal fun configureAssembleTaskGraph(project: Project, ext: WasmlineExtension) {
        val componentBuild = ext.manifest.executionModel.get() == WasmlineExecutionModel.COMPONENT_MODEL
        val directComponent = ext.component.componentInput.isPresent
        val generateBindings = if (componentBuild && !directComponent) {
            registerGenerateBindingsTask(project, ext)
        } else {
            null
        }
        val debugComponent = project.tasks.named(
            "wasmlineComponentizeDebug",
            WasmlineComponentizeTask::class.java,
        )
        val releaseComponent = project.tasks.named(
            "wasmlineComponentizeRelease",
            WasmlineComponentizeTask::class.java,
        )
        val debugComponentAot = project.tasks.named(
            "wasmlineComponentAotDebug",
            WasmlineComponentAotTask::class.java,
        )
        val releaseComponentAot = project.tasks.named(
            "wasmlineComponentAotRelease",
            WasmlineComponentAotTask::class.java,
        )
        val debugCompileTask = "compileDevelopmentLibraryKotlinWasmWasiOptimize"
        val releaseCompileTask = "compileProductionLibraryKotlinWasmWasiOptimize"

        if (generateBindings != null) {
            project.extensions.getByType(KotlinMultiplatformExtension::class.java)
                .sourceSets
                .getByName("wasmWasiMain")
                .kotlin
                .srcDir(generateBindings.flatMap { it.outputDirectory })
        }

        project.tasks.matching { it.name == debugCompileTask || it.name == releaseCompileTask }
            .configureEach { task ->
                if (generateBindings != null) task.dependsOn(generateBindings)
            }

        configureComponentizeInputs(
            task = debugComponent,
            project = project,
            ext = ext,
            componentBuild = componentBuild,
            directComponent = directComponent,
            compileTaskName = debugCompileTask,
            libraryDir = "developmentLibrary",
        )
        configureComponentizeInputs(
            task = releaseComponent,
            project = project,
            ext = ext,
            componentBuild = componentBuild,
            directComponent = directComponent,
            compileTaskName = releaseCompileTask,
            libraryDir = "productionLibrary",
        )

        project.tasks.named("wasmlineAssembleDebug", WasmlineAssembleTask::class.java).configure { task ->
            if (componentBuild) {
                task.componentOutputDirectory.set(debugComponentAot.flatMap { it.outputDirectory })
                task.dependsOn(debugComponentAot)
            } else {
                task.wasmCompileOutputDir.set(
                    project.layout.buildDirectory.dir(
                        "compileSync/wasmWasi/main/developmentLibrary/optimized",
                    ),
                )
                task.dependsOn(debugCompileTask)
            }
        }
        project.tasks.named("wasmlineAssembleRelease", WasmlineAssembleTask::class.java).configure { task ->
            if (componentBuild) {
                task.componentOutputDirectory.set(releaseComponentAot.flatMap { it.outputDirectory })
                task.dependsOn(releaseComponentAot)
            } else {
                task.wasmCompileOutputDir.set(
                    project.layout.buildDirectory.dir(
                        "compileSync/wasmWasi/main/productionLibrary/optimized",
                    ),
                )
                task.dependsOn(releaseCompileTask)
            }
        }
    }

    private fun registerComponentizeTask(project: Project, ext: WasmlineExtension, taskName: String, variantName: String) =
        project.tasks.register(taskName, WasmlineComponentizeTask::class.java) { task ->
            task.group = "wasmline"
            task.description = "Create the " + variantName + " Component Wasm"
            task.outputDirectory.set(ext.component.outputDirectory.dir(variantName))
            task.productName.set(ext.manifest.pluginId.map { id -> id.substringAfterLast('.') })
            task.world.set(ext.component.world)
            task.invocationProtocol.set(ext.manifest.invocationProtocol)
            task.exportName.set(ext.component.exportName.orElse(ext.manifest.exportName))
            task.codec.set(ext.component.codec)
            task.serviceProtocolVersion.set(ext.component.serviceProtocolVersion)
            task.witBindgenVersion.set(ext.component.witBindgenVersion)
            task.wasmToolsVersion.set(ext.component.wasmToolsVersion)
            task.platform.convention(detectCurrentPlatform())
            task.autoDownload.set(ext.component.autoDownload)
            task.wasmToolsExecutable.set(ext.component.wasmToolsExecutable)
            task.wasiPreview1Adapter.set(ext.component.wasiPreview1Adapter)
            task.componentInput.set(ext.component.componentInput)
            task.toolCacheDirectory.set(ext.component.toolCacheDirectory)
            task.githubToken.set(ext.wasmtime.githubToken)
        }

    private fun registerComponentAotTask(
        project: Project,
        ext: WasmlineExtension,
        componentTask: TaskProvider<WasmlineComponentizeTask>,
        taskName: String,
        variantName: String,
    ) = project.tasks.register(taskName, WasmlineComponentAotTask::class.java) { task ->
        task.group = "wasmline"
        task.description = "Compile the $variantName Component to native CWASM/PWASM artifacts"
        task.componentDirectory.set(componentTask.flatMap { it.outputDirectory })
        task.componentRecordFile.set(
            componentTask.flatMap { it.outputDirectory.file(ComponentBuildRecords.FILE_NAME) },
        )
        task.wasmtimeCompilerExecutable.set(
            project.layout.file(
                project.provider {
                    ext.wasmtime.compilerExecutable.orNull?.asFile ?: run {
                        val directory = ext.wasmtime.compilerDirectory.get().asFile
                        val version = ext.wasmtime.compilerVersion.get()
                        WasmtimeCompiler.findWasmtimeCompilerInDirectory(
                            baseDir = directory,
                            platform = detectCurrentPlatform(),
                            version = version,
                        ) ?: if (ext.wasmtime.autoDownload.get()) {
                            File(directory, fullWasmtimeExecutableName())
                        } else {
                            throw GradleException(
                                "Full Wasmtime CLI $version was not found in ${directory.absolutePath}. " +
                                    "Configure wasmline.wasmtime.compilerExecutable or run " +
                                    "./gradlew wasmlineDownloadWasmtimeCompiler.",
                            )
                        }
                    }
                },
            ),
        )
        task.wasmtimeVersion.set(ext.wasmtime.compilerVersion)
        task.targets.set(ext.wasmtime.targets)
        task.productName.set(ext.manifest.pluginId.map { id -> id.substringAfterLast('.') })
        task.outputDirectory.set(project.layout.buildDirectory.dir("wasmline/component-aot/$variantName"))
        task.dependsOn(componentTask)
        task.dependsOn(
            project.provider<List<String>> {
                if (!ext.wasmtime.compilerExecutable.isPresent && ext.wasmtime.autoDownload.get()) {
                    listOf("wasmlineDownloadWasmtimeCompiler")
                } else {
                    emptyList()
                }
            },
        )
    }

    private fun configureComponentizeInputs(
        task: TaskProvider<WasmlineComponentizeTask>,
        project: Project,
        ext: WasmlineExtension,
        componentBuild: Boolean,
        directComponent: Boolean,
        compileTaskName: String,
        libraryDir: String,
    ) {
        if (!componentBuild) return
        task.configure { componentizeTask ->
            if (directComponent) {
                // A finished Component is self-contained. Track an optional WIT
                // directory only when it exists for metadata/digest purposes.
                ext.component.witDirectory.orNull?.asFile?.takeIf(File::isDirectory)?.let {
                    componentizeTask.witDirectory.set(it)
                }
            } else {
                componentizeTask.wasmCompileOutputDirectory.set(
                    project.layout.buildDirectory.dir(
                        "compileSync/wasmWasi/main/$libraryDir/optimized",
                    ),
                )
                val generatedBindings = project.tasks.named(
                    "wasmlineGenerateWitBindings",
                    WasmlineGenerateWitBindingsTask::class.java,
                )
                componentizeTask.witDirectory.set(
                    ext.manifest.invocationProtocol.flatMap { protocol ->
                        if (protocol == WasmlineInvocationProtocol.WASMLINE_SERVICE) {
                            generatedBindings.flatMap { it.outputDirectory.dir("wit") }
                        } else {
                            ext.component.witDirectory
                        }
                    },
                )
                componentizeTask.dependsOn(compileTaskName)
                componentizeTask.dependsOn(generatedBindings)
            }
        }
    }

    private fun configureAssembleTask(task: WasmlineAssembleTask, project: Project, ext: WasmlineExtension) {
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
        task.executionModel.set(manifestExt.executionModel)
        task.invocationProtocol.set(manifestExt.invocationProtocol)
        task.exportName.set(manifestExt.exportName)
        task.contractMetadata.set(manifestExt.contractMetadata)
        task.manifestToolClasspath.from(
            project.provider {
                project.buildscript.configurations.getByName("classpath").files
                    .filterNot { file -> file.name.startsWith("construo-") }
                    .sortedWith(
                        compareBy<File> { file -> manifestToolClasspathPriority(file) }
                            .thenBy(File::getName),
                    )
            },
        )

        // Wasmtime configuration
        task.wasmtimeDirectory.set(ext.wasmtime.directory)
        task.compileTargets.set(ext.wasmtime.targets)
        task.wasmtimeVersion.set(ext.wasmtime.version)

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

    private fun isWasmWasiCompilation(kotlinCompilation: KotlinCompilation<*>): Boolean =
        kotlinCompilation.target.platformType == KotlinPlatformType.wasm &&
            kotlinCompilation.defaultSourceSet.name == "wasmWasiMain"

    private fun resolveGuestTransport(
        compilation: KotlinCompilation<*>,
        executionModel: WasmlineExecutionModel,
        invocationProtocol: WasmlineInvocationProtocol,
    ): String = when (executionModel) {
        WasmlineExecutionModel.CORE_WASM -> when (invocationProtocol) {
            WasmlineInvocationProtocol.WASMLINE_SERVICE -> "CORE"
            WasmlineInvocationProtocol.RAW_EXPORT -> "NONE"
            else -> throw GradleException("CORE_WASM cannot use invocation protocol $invocationProtocol.")
        }

        WasmlineExecutionModel.COMPONENT_MODEL -> when (invocationProtocol) {
            WasmlineInvocationProtocol.COMPONENT_EXPORT -> "NONE"

            WasmlineInvocationProtocol.WASMLINE_SERVICE ->
                if (isWasmWasiCompilation(compilation)) "COMPONENT_SERVICE" else "NONE"

            else -> throw GradleException("COMPONENT_MODEL cannot use invocation protocol $invocationProtocol.")
        }
    }

    private fun manifestToolClasspathPriority(file: File): Int = when {
        file.name.startsWith("kotlinx-serialization-core") -> 0
        file.name.startsWith("kotlinx-serialization-json") -> 1
        file.name.startsWith("kotlinx-serialization-protobuf") -> 2
        file.name.startsWith("wasmline-gradle-plugin") -> 3
        else -> 10
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
        const val GUEST_TRANSPORT_OPTION = "guestTransport"
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
