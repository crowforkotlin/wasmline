package crow.wasmline.gradle.tasks

import crow.wasmline.plugin.core.component.ComponentAotBuildRecords
import crow.wasmline.plugin.core.component.ComponentAotPipeline
import crow.wasmline.plugin.core.component.ComponentAotPipelineRequest
import crow.wasmline.plugin.core.component.ComponentAotTargetFactory
import crow.wasmline.plugin.core.component.ComponentBuildRecords
import crow.wasmline.plugin.core.component.ComponentCompiler
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Compiles a raw Component build record into native CWASM/PWASM artifacts. */
@CacheableTask
internal abstract class WasmlineComponentAotTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val componentDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val componentRecordFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val wasmtimeCompilerExecutable: RegularFileProperty

    @get:Input
    abstract val wasmtimeVersion: Property<String>

    @get:Input
    abstract val targets: ListProperty<String>

    @get:Input
    abstract val productName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun compileComponentAot() {
        val componentRoot = componentDirectory.get().asFile
        val rawRecord = ComponentBuildRecords.read(componentRecordFile.get().asFile)
        val outputRoot = outputDirectory.get().asFile
        val nativeTargets = ComponentAotTargetFactory.create(
            outputDirectory = outputRoot,
            productName = productName.get(),
            targets = targets.get(),
        )
        val exactVersion = normalizeExactVersion(wasmtimeVersion.get())
        val result = ComponentAotPipeline(
            ComponentCompiler(logger = { message -> logger.info(message) }),
        ).compile(
            rawComponent = rawRecord,
            componentDirectory = componentRoot,
            request = ComponentAotPipelineRequest(
                wasmtimeCompiler = wasmtimeCompilerExecutable.get().asFile,
                wasmtimeVersion = exactVersion,
                targets = nativeTargets,
                outputRecord = outputRoot.resolve(ComponentAotBuildRecords.FILE_NAME),
            ),
        )
        logger.lifecycle(
            "Component AOT artifacts: " + result.aotRecord.artifacts.joinToString { artifact -> artifact.url },
        )
    }

    private fun normalizeExactVersion(configuredVersion: String): String {
        val version = configuredVersion.trim().removePrefix("release-").removePrefix("v")
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) {
            "Component AOT requires an exact Wasmtime x.y.z version, not '$configuredVersion'."
        }
        return version
    }
}
