package crow.wasmline.plugin.core.component

import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import java.io.File

/** Native AOT stage configuration shared by Gradle and the CLI. */

@InternalWasmlineToolingApi
data class ComponentAotPipelineRequest(
    val wasmtimeCompiler: File,
    val wasmtimeVersion: String,
    val targets: List<ComponentAotTarget>,
    val outputRecord: File,
    val engineOptions: ComponentAotEngineOptions = ComponentAotEngineOptions(),
) {
    init {
        require(targets.isNotEmpty()) { "Native Component packaging requires at least one CWASM or PWASM target." }
        require(outputRecord.parentFile != null) { "Component AOT record requires a parent directory." }
    }
}

/** Complete raw-to-AOT result returned to build-system adapters. */

@InternalWasmlineToolingApi
data class ComponentAotPipelineResult(
    val rawComponent: ComponentBuildRecord,
    val compileResult: ComponentAotCompileResult,
    val aotRecord: ComponentAotBuildRecord,
    val aotRecordFile: File,
)

/** Chains a finished raw Component into mandatory native CWASM/PWASM outputs. */

@InternalWasmlineToolingApi
class ComponentAotPipeline internal constructor(private val compileComponent: (ComponentAotCompileRequest) -> ComponentAotCompileResult) {
    constructor(compiler: ComponentCompiler) : this(compiler::compile)

    /** Persists a new raw Component result before compiling all native targets. */
    fun compile(
        componentResult: ComponentizeResult,
        componentRecordFile: File,
        request: ComponentAotPipelineRequest,
    ): ComponentAotPipelineResult {
        val rawComponent = ComponentBuildRecords.write(componentResult, componentRecordFile)
        val componentDirectory = componentRecordFile.parentFile
            ?: error("Component result file requires a parent directory.")
        return compile(rawComponent, componentDirectory, request)
    }

    /** Verifies an existing raw Component record before compiling all native targets. */
    fun compile(
        rawComponent: ComponentBuildRecord,
        componentDirectory: File,
        request: ComponentAotPipelineRequest,
    ): ComponentAotPipelineResult {
        validateOutputLocations(request)
        val rawArtifact = rawComponent.toArtifact(componentDirectory)
        val componentFile = rawComponent.resolveComponentFile(componentDirectory)
        val artifactMetadata = ComponentAotArtifactMetadata(
            invocationProtocol = rawArtifact.invocationProtocol,
            exportName = rawArtifact.exportName,
            contractMetadata = rawArtifact.contractMetadata,
        )
        val compileResult = compileComponent(
            ComponentAotCompileRequest(
                wasmtimeCompiler = request.wasmtimeCompiler,
                inputComponent = componentFile,
                targets = request.targets,
                wasmtimeVersion = request.wasmtimeVersion,
                engineOptions = request.engineOptions,
                artifactMetadata = artifactMetadata,
            ),
        )
        val aotRecord = ComponentAotBuildRecords.write(rawComponent, compileResult, request.outputRecord)
        return ComponentAotPipelineResult(
            rawComponent = rawComponent,
            compileResult = compileResult,
            aotRecord = aotRecord,
            aotRecordFile = request.outputRecord,
        )
    }

    private fun validateOutputLocations(request: ComponentAotPipelineRequest) {
        val outputDirectory = requireNotNull(request.outputRecord.parentFile)
            .toPath()
            .toAbsolutePath()
            .normalize()
        request.targets.forEach { target ->
            val output = target.outputFile.toPath().toAbsolutePath().normalize()
            require(output.startsWith(outputDirectory)) {
                "Component AOT target output is outside the AOT record directory: " + target.outputFile.absolutePath
            }
        }
    }
}
