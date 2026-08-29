@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline

import crow.wasmline.gradle.BuildConfig
import crow.wasmline.gradle.WasmlineBuildVariant
import crow.wasmline.gradle.WasmtimeTarget
import crow.wasmline.gradle.extensions.WasmlineExtension
import crow.wasmline.gradle.tasks.DownloadComponentToolsTask
import crow.wasmline.gradle.tasks.WasmlineAotBuildTask
import crow.wasmline.gradle.tasks.WasmlineAssembleStateService
import crow.wasmline.gradle.tasks.WasmlineAssembleTask
import crow.wasmline.gradle.tasks.WasmlineCheckAotCompatibilityTask
import crow.wasmline.gradle.tasks.WasmlineComponentizeTask
import crow.wasmline.gradle.tasks.WasmlineGenerateHostWitBindingsTask
import crow.wasmline.gradle.tasks.WasmlineGenerateWitBindingsTask
import crow.wasmline.gradle.tasks.WasmlineMarkAssembleAttemptTask
import crow.wasmline.gradle.tasks.WasmlineServerDeployTask
import crow.wasmline.plugin.core.aot.WasmlineAotTargetSpec
import crow.wasmline.plugin.core.aot.WasmlineArtifactTargetFactory
import crow.wasmline.plugin.core.aot.WasmlineRawAbiMetadataCodec
import crow.wasmline.plugin.core.manifest.ManifestKeyGenerator
import crow.wasmline.plugin.core.util.PlatformDetector
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.build.event.BuildEventsListenerRegistry
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
import javax.inject.Inject
import kotlin.jvm.java

/**
 * Wasmline Gradle plugin — bridges the Kotlin IR compiler plugin and
 * registers build / deploy tasks for wasmline plugins.
 *
 * Primary tasks:
 * - `wasmlineAssembleDebug` — assemble with Development compilation output
 * - `wasmlineAssembleRelease` — assemble with Production compilation output
 * - `wasmlineServerDeploy` — start an HTTP server serving the assembled artifacts
 *
 * Toolchain and signing tasks:
 * - `wasmlineDownloadComponentTools` — download Component Model tools
 * - `generateWasmlineManifestKeyPairEd25519` — generate an Ed25519 manifest key pair
 *
 * Component pipeline tasks:
 * - `wasmlineGenerateWitBindings`
 * - `wasmlineGenerateHostWitBindings`
 * - `wasmlineComponentizeDebug` / `wasmlineComponentizeRelease`
 * - `wasmlineAotBuildDebug` / `wasmlineAotBuildRelease`
 *
 * DSL usage (in the consuming project's `build.gradle.kts`):
 * ```kotlin
 * wasmline {
 *     manifest {
 *         pluginId = "crow.wasmline.demo"
 *         version = "1.0.0"
 *         signingKey = file("../keys/private.key")
 *     }
 *     wasmtime {
 *         aotCompatibility { current() }
 *         targets = listOf(WasmtimeTarget.PULLEY_64, WasmtimeTarget.X86_64_LINUX)
 *         autoDownload.set(true)
 *     }
 *     server {
 *         port = 8080
 *         deployVariant = WasmlineBuildVariant.RELEASE
 *     }
 * }
 * ```
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
public class WasmlinePlugin private constructor(@Suppress("UNUSED_PARAMETER") marker: Unit) : KotlinCompilerPluginSupportPlugin {
    private var buildEventsListenerRegistry: BuildEventsListenerRegistry? = null

    /** Creates the plugin for isolated unit tests without Gradle service injection. */
    public constructor() : this(Unit)

    /** Creates the plugin with Gradle's build event registry. */
    @Inject
    public constructor(buildEventsListenerRegistry: BuildEventsListenerRegistry) : this(Unit) {
        this.buildEventsListenerRegistry = buildEventsListenerRegistry
    }

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
        target.pluginManager.apply(WasmlineRuntimePlugin::class.java)

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
                createComponentDownloadTask(project = target, ext = extension)
                createGenerateKeyPairTasks(project = target)
                registerAssembleTasks(project = target, ext = extension)
                registerServerDeployTask(project = target, ext = extension)
            }
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

    private fun detectCurrentPlatform(): String = PlatformDetector.detectPlatform()

    // ==================== Task registration ====================

    private fun registerAssembleTasks(project: Project, ext: WasmlineExtension) {
        val debugCompileTask = "compileDevelopmentLibraryKotlinWasmWasiOptimize"
        val releaseCompileTask = "compileProductionLibraryKotlinWasmWasiOptimize"
        val debugAssemblePath = "${project.path}:${WasmlineBuildVariant.DEBUG.assembleTaskName}"
        val releaseAssemblePath = "${project.path}:${WasmlineBuildVariant.RELEASE.assembleTaskName}"
        val stateServiceName = "wasmlineAssembleState" + project.path
            .replace(Regex("[^A-Za-z0-9_]"), "_")
        val assembleStateService = project.gradle.sharedServices.registerIfAbsent(
            stateServiceName,
            WasmlineAssembleStateService::class.java,
        ) { specification ->
            specification.parameters.assembleTaskPaths.set(listOf(debugAssemblePath, releaseAssemblePath))
        }
        buildEventsListenerRegistry?.onTaskCompletion(assembleStateService)

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
        val debugAot = registerAotBuildTask(project, ext, "wasmlineAotBuildDebug", "debug")
        val releaseAot = registerAotBuildTask(project, ext, "wasmlineAotBuildRelease", "release")
        val debugAttempt = registerAssembleAttemptTask(project, assembleStateService, "wasmlineMarkAssembleDebug")
        val releaseAttempt = registerAssembleAttemptTask(project, assembleStateService, "wasmlineMarkAssembleRelease")
        debugAot.configure { task -> task.mustRunAfter(debugAttempt) }
        releaseAot.configure { task -> task.mustRunAfter(releaseAttempt) }

        val debugAssemble = project.tasks.register(
            WasmlineBuildVariant.DEBUG.assembleTaskName,
            WasmlineAssembleTask::class.java,
        ) { task ->
            task.description = "Assemble wasmline plugin (debug / Development variant)"
            task.buildVariant.set(WasmlineBuildVariant.DEBUG.compilationName)
            task.assembleStateService.set(assembleStateService)
            task.usesService(assembleStateService)
            configureAssembleTask(task, project, ext)
            task.aotOutputDirectory.set(debugAot.flatMap { it.outputDirectory })
        }

        val releaseAssemble = project.tasks.register(
            WasmlineBuildVariant.RELEASE.assembleTaskName,
            WasmlineAssembleTask::class.java,
        ) { task ->
            task.description = "Assemble wasmline plugin (release / Production variant)"
            task.buildVariant.set(WasmlineBuildVariant.RELEASE.compilationName)
            task.assembleStateService.set(assembleStateService)
            task.usesService(assembleStateService)
            configureAssembleTask(task, project, ext)
            task.aotOutputDirectory.set(releaseAot.flatMap { it.outputDirectory })
        }

        val compatibilityCheck = registerCompatibilityCheckTask(
            project = project,
            ext = ext,
            stateService = assembleStateService,
        )
        configureCompatibilityCheckFinalizers(
            compatibilityCheck = compatibilityCheck,
            debugAssemble = debugAssemble,
            releaseAssemble = releaseAssemble,
            debugAttempt = debugAttempt,
            releaseAttempt = releaseAttempt,
        )
    }

    private fun registerAssembleAttemptTask(
        project: Project,
        stateService: Provider<WasmlineAssembleStateService>,
        taskName: String,
    ): TaskProvider<WasmlineMarkAssembleAttemptTask> = project.tasks.register(
        taskName,
        WasmlineMarkAssembleAttemptTask::class.java,
    ) { task ->
        task.stateService.set(stateService)
        task.usesService(stateService)
    }

    private fun configureCompatibilityCheckFinalizers(
        compatibilityCheck: TaskProvider<WasmlineCheckAotCompatibilityTask>,
        debugAssemble: TaskProvider<WasmlineAssembleTask>,
        releaseAssemble: TaskProvider<WasmlineAssembleTask>,
        debugAttempt: TaskProvider<WasmlineMarkAssembleAttemptTask>,
        releaseAttempt: TaskProvider<WasmlineMarkAssembleAttemptTask>,
    ) {
        compatibilityCheck.configure { task ->
            task.mustRunAfter(debugAssemble, releaseAssemble)
        }
        debugAssemble.configure { task ->
            task.dependsOn(debugAttempt)
            task.finalizedBy(compatibilityCheck)
        }
        releaseAssemble.configure { task ->
            task.dependsOn(releaseAttempt)
            task.finalizedBy(compatibilityCheck)
        }
    }

    private fun registerCompatibilityCheckTask(
        project: Project,
        ext: WasmlineExtension,
        stateService: Provider<WasmlineAssembleStateService>,
    ) = project.tasks.register("wasmlineCheckAotCompatibility", WasmlineCheckAotCompatibilityTask::class.java) { task ->
        task.assembleStateService.set(stateService)
        task.usesService(stateService)
        task.aotCompatibilitySelector.set(ext.wasmtime.aotCompatibility.selectorKind)
        task.aotCompatibilityRanges.set(ext.wasmtime.aotCompatibility.selectorRanges)
        task.minSdkVersion.set(ext.manifest.minSdkVersion)
        task.requestedBackends.set(project.provider { requestedAotBackends(ext) })
        task.nativeTargetCount.set(project.provider { requestedAotTargetSpecs(ext).size })
        task.offline.set(project.provider { project.gradle.startParameter.isOffline })
        task.suppressCompatibilityWarning.set(ext.wasmtime.aotCompatibility.suppressCompatibilityWarning)
        task.reportFile.set(project.layout.buildDirectory.file("reports/wasmline/aot-compatibility-check.json"))
        task.cacheDirectory.set(
            File(System.getProperty("user.home"), ".gradle/caches/wasmline/aot-compatibility"),
        )
    }

    private fun requestedAotTargetSpecs(ext: WasmlineExtension): List<WasmlineAotTargetSpec> =
        WasmlineArtifactTargetFactory.create(ext.wasmtime.targets.map(WasmtimeTarget::targetName))

    private fun requestedAotBackends(ext: WasmlineExtension): List<String> = requestedAotTargetSpecs(ext)
        .map(WasmlineAotTargetSpec::artifactBackend)
        .map(WasmlineEngineKind::name)
        .distinct()
        .sorted()

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
        val debugAot = project.tasks.named("wasmlineAotBuildDebug", WasmlineAotBuildTask::class.java)
        val releaseAot = project.tasks.named("wasmlineAotBuildRelease", WasmlineAotBuildTask::class.java)
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

        project.tasks.named(
            WasmlineBuildVariant.DEBUG.assembleTaskName,
            WasmlineAssembleTask::class.java,
        ).configure { task ->
            task.dependsOn(debugAot)
        }
        project.tasks.named(
            WasmlineBuildVariant.RELEASE.assembleTaskName,
            WasmlineAssembleTask::class.java,
        ).configure { task ->
            task.dependsOn(releaseAot)
        }
        configureAotBuildTask(
            task = debugAot,
            project = project,
            ext = ext,
            componentTask = debugComponent,
            componentBuild = componentBuild,
            compileTaskName = debugCompileTask,
            libraryDir = "developmentLibrary",
        )
        configureAotBuildTask(
            task = releaseAot,
            project = project,
            ext = ext,
            componentTask = releaseComponent,
            componentBuild = componentBuild,
            compileTaskName = releaseCompileTask,
            libraryDir = "productionLibrary",
        )
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

    private fun registerAotBuildTask(project: Project, ext: WasmlineExtension, taskName: String, variantName: String) =
        project.tasks.register(taskName, WasmlineAotBuildTask::class.java) { task ->
            task.productName.set(ext.manifest.pluginId.map { id -> id.substringAfterLast('.') })
            task.executionModel.set(ext.manifest.executionModel)
            task.invocationProtocol.set(ext.manifest.invocationProtocol)
            task.exportName.set(ext.manifest.exportName)
            task.contractMetadata.set(ext.manifest.contractMetadata)
            task.rawAbiMetadataJson.set(ext.manifest.rawAbi.map(WasmlineRawAbiMetadataCodec::encode))
            task.targets.set(ext.wasmtime.targetsProvider.map { targets -> targets.map(WasmtimeTarget::targetName) })
            task.aotCompatibilitySelector.set(ext.wasmtime.aotCompatibility.selectorKind)
            task.aotCompatibilityRanges.set(ext.wasmtime.aotCompatibility.selectorRanges)
            task.minSdkVersion.set(ext.manifest.minSdkVersion)
            task.autoDownload.set(ext.wasmtime.autoDownload)
            task.buildHost.set(detectCurrentPlatform())
            task.maxParallelCompilations.set(ext.wasmtime.maxParallelCompilations)
            task.githubToken.set(ext.wasmtime.githubToken)
            task.compilerCacheDirectory.set(ext.wasmtime.compilerCacheDirectory)
            task.outputDirectory.set(project.layout.buildDirectory.dir("wasmline/aot/$variantName"))
        }

    private fun configureAotBuildTask(
        task: TaskProvider<WasmlineAotBuildTask>,
        project: Project,
        ext: WasmlineExtension,
        componentTask: TaskProvider<WasmlineComponentizeTask>,
        componentBuild: Boolean,
        compileTaskName: String,
        libraryDir: String,
    ) {
        task.configure { aot ->
            if (componentBuild) {
                aot.componentOutputDirectory.set(componentTask.flatMap { it.outputDirectory })
                aot.dependsOn(componentTask)
            } else {
                aot.coreWasmCompileOutputDirectory.set(
                    project.layout.buildDirectory.dir("compileSync/wasmWasi/main/$libraryDir/optimized"),
                )
                aot.dependsOn(compileTaskName)
            }
        }
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
        task.buildTimestamp.set(manifestExt.buildTimestamp)

        // Output directory: build/wasmline/output/
        task.outputDirectory.set(
            project.layout.buildDirectory.dir("wasmline/output"),
        )
        task.distributionDirectory.set(project.layout.buildDirectory.dir("wasmline/dist"))
    }

    private fun registerServerDeployTask(project: Project, ext: WasmlineExtension) {
        project.tasks.register("wasmlineServerDeploy", WasmlineServerDeployTask::class.java) { task ->
            task.port.set(ext.server.port)
            task.host.set(ext.server.host)

            task.dependsOn(ext.server.deployVariant.map(WasmlineBuildVariant::assembleTaskName))

            task.serveDirectory.set(
                ext.manifest.pluginId.zip(ext.manifest.version) { pluginId, version ->
                    project.layout.buildDirectory.get().dir("wasmline/output/$pluginId-$version")
                },
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
            task.description = "Generate an Ed25519 manifest signing key pair"
            task.doLast {
                generateKeyPair()
            }
        }
    }

    private companion object {
        const val GUEST_TRANSPORT_OPTION = "guestTransport"
    }

    private fun generateKeyPair() {
        val logger = LoggerFactory.getLogger(WasmlinePlugin::class.java)
        val keyPair = ManifestKeyGenerator.generate()
        logger.warn("---------------- ----------------------------------------------------------------")
        logger.warn("    ALGORITHM: Ed25519")
        logger.warn("    PUBLIC KEY: ${keyPair.publicKeyHex}")
        logger.warn("    PRIVATE KEY: ${keyPair.privateKeyHex}")
        logger.warn("---------------- ----------------------------------------------------------------")
    }
}
