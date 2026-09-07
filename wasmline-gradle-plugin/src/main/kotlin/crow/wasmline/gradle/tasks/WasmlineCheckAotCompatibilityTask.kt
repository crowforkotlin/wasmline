package crow.wasmline.gradle.tasks

import crow.wasmline.WasmlineEngineKind
import crow.wasmline.plugin.core.aot.AotCompatibilityCatalog
import crow.wasmline.plugin.core.aot.WasmlineAotCompatibilityCheckRequest
import crow.wasmline.plugin.core.aot.WasmlineAotCompatibilityChecker
import crow.wasmline.plugin.core.aot.decodeAotCompatibilitySelection
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Checks a completed native AOT selection against the latest published catalog.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@DisableCachingByDefault(because = "The advisory check observes bounded remote release state.")
internal abstract class WasmlineCheckAotCompatibilityTask : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val aotCompatibilitySelector: Property<String>

    @get:Input
    abstract val aotCompatibilityRanges: ListProperty<String>

    @get:Input
    abstract val minSdkVersion: Property<String>

    @get:Input
    abstract val requestedBackends: ListProperty<String>

    @get:Input
    abstract val nativeTargetCount: Property<Int>

    @get:Input
    abstract val offline: Property<Boolean>

    @get:Input
    abstract val suppressCompatibilityWarning: Property<Boolean>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @get:Internal
    abstract val cacheDirectory: DirectoryProperty

    @get:Internal
    abstract val assembleStateService: Property<WasmlineAssembleStateService>

    init {
        group = "verification"
        description = "Checks the built AOT profile selection against the latest published Wasmline compatibility catalog."
        outputs.upToDateWhen { false }
    }

    /** Writes a report and emits one bounded compatibility warning when appropriate. */
    @TaskAction
    fun checkCompatibility() {
        if (assembleStateService.orNull?.shouldRunAutomaticCheck() == false) {
            logger.info("Skipping automatic AOT compatibility check because no Wasmline assemble task succeeded.")
            return
        }
        val selectorName = aotCompatibilitySelector.orNull
            ?: throw GradleException(
                "[WLAOT100] An explicit AOT compatibility selector is required before running the compatibility check. " +
                    "Configure aotCompatibility { current() }, minimum(), all(), or versionRanges { ... }.",
            )
        val backends = requestedBackends.get().map { name ->
            runCatching { WasmlineEngineKind.valueOf(name) }.getOrElse {
                throw GradleException("Invalid AOT compatibility backend '$name'.")
            }
        }.toSet()
        val localCatalog = AotCompatibilityCatalog.publicReleaseCatalog()
        val selection = decodeAotCompatibilitySelection(selectorName, aotCompatibilityRanges.get())
        val resolution = AotCompatibilityCatalog.resolveSelection(
            selection = selection,
            manifestMinimumWasmlineVersion = minSdkVersion.get(),
            artifactBackends = backends,
        )
        WasmlineAotCompatibilityChecker().check(
            WasmlineAotCompatibilityCheckRequest(
                localCatalog = localCatalog,
                localWasmlineVersion = localCatalog.currentWasmlineVersion,
                selector = selectorName,
                selectedAotGenerations = resolution.selectedGenerations,
                selectedProfileIds = resolution.profiles.map { it.id },
                nativeTargetCount = nativeTargetCount.get(),
                requestedBackends = backends,
                reportFile = reportFile.get().asFile,
                cacheDirectory = cacheDirectory.get().asFile,
                offline = offline.get(),
                warningSuppressed = suppressCompatibilityWarning.get(),
                logger = logger::warn,
            ),
        )
    }
}
