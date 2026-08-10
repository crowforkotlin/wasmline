package crow.wasmline.cli

import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import crow.wasmline.plugin.core.component.ComponentAotBuildRecord
import crow.wasmline.plugin.core.component.ComponentAotBuildRecords
import crow.wasmline.plugin.core.component.ComponentAotPipeline
import crow.wasmline.plugin.core.component.ComponentAotPipelineRequest
import crow.wasmline.plugin.core.component.ComponentAotTargetFactory
import crow.wasmline.plugin.core.component.ComponentBuildRecord
import crow.wasmline.plugin.core.component.ComponentCompiler
import java.io.File

internal data class ComponentAotCliRequest(
    val rawComponent: ComponentBuildRecord,
    val componentDirectory: File,
    val outputDirectory: File,
    val productName: String,
    val wasmtimeDirectory: File,
    val targets: Collection<String>,
    val wasmtimeVersion: String,
)

internal data class ComponentAotCliResult(val artifacts: List<WasmlineArtifact>, val wasmtimeVersion: String, val recordFile: File)

internal fun interface ComponentAotCompilerResolver {
    fun resolve(directory: File, version: String): File?
}

internal fun interface ComponentAotPipelineRunner {
    fun compile(
        rawComponent: ComponentBuildRecord,
        componentDirectory: File,
        request: ComponentAotPipelineRequest,
    ): ComponentAotBuildRecord
}

/** CLI boundary for mandatory native Component AOT compilation. */
internal class ComponentAotCliAdapter(
    private val compilerResolver: ComponentAotCompilerResolver,
    private val pipelineRunner: ComponentAotPipelineRunner,
) {
    constructor(logger: (String) -> Unit = {}) : this(
        compilerResolver = ComponentAotCompilerResolver { directory, version ->
            WasmtimeCompiler.findWasmtimeCompilerInDirectory(directory, version = version)
        },
        pipelineRunner = ComponentAotPipelineRunner { rawComponent, componentDirectory, request ->
            ComponentAotPipeline(ComponentCompiler(logger)).compile(
                rawComponent = rawComponent,
                componentDirectory = componentDirectory,
                request = request,
            ).aotRecord
        },
    )

    fun compile(request: ComponentAotCliRequest): ComponentAotCliResult {
        val compiler = compilerResolver.resolve(request.wasmtimeDirectory, request.wasmtimeVersion)
            ?: error(
                "Component AOT requires the full Wasmtime ${request.wasmtimeVersion} CLI in " +
                    request.wasmtimeDirectory.absolutePath +
                    ". wasmtime-min is runtime-only; use 'wasmline download --distribution full'.",
            )
        val outputDirectory = request.outputDirectory.apply {
            check(exists() || mkdirs()) { "Unable to create Component AOT output directory: $absolutePath" }
        }
        val nativeTargets = ComponentAotTargetFactory.create(
            outputDirectory = outputDirectory,
            productName = request.productName,
            targets = request.targets,
        )
        val recordFile = File(outputDirectory, ComponentAotBuildRecords.FILE_NAME)
        val record = pipelineRunner.compile(
            rawComponent = request.rawComponent,
            componentDirectory = request.componentDirectory,
            request = ComponentAotPipelineRequest(
                wasmtimeCompiler = compiler,
                wasmtimeVersion = request.wasmtimeVersion,
                targets = nativeTargets,
                outputRecord = recordFile,
            ),
        )
        require(record.wasmtimeVersion == request.wasmtimeVersion) {
            "Component AOT record Wasmtime version does not match the CLI request."
        }
        val artifacts = record.resolveArtifacts(outputDirectory).map { it.artifact }
        return ComponentAotCliResult(
            artifacts = artifacts,
            wasmtimeVersion = record.wasmtimeVersion,
            recordFile = recordFile,
        )
    }
}
