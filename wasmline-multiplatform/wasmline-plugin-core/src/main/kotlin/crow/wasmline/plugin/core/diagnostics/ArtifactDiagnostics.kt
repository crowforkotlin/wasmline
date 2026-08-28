package crow.wasmline.plugin.core.diagnostics

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import crow.wasmline.plugin.core.aot.WasmlineAotBuildRecord
import crow.wasmline.plugin.core.aot.WasmlineCompiledArtifact

/**
 * Describes one compiled content object without exposing filesystem-specific paths.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
data class WasmlineArtifactDiagnostic(
    val artifact: String,
    val format: WasmlineArtifactFormat,
    val executionModel: WasmlineExecutionModel,
    val artifactBackend: WasmlineEngineKind?,
    val target: String,
    val wasmtimeVersion: String?,
    val aotCompatibilityProfileId: String?,
) {
    /** Renders one stable diagnostic line. */
    fun render(): String = buildString {
        append("artifact=").append(artifact)
        append(" format=").append(format.name)
        append(" executionModel=").append(executionModel.name)
        append(" backend=").append(artifactBackend?.name ?: "RAW")
        append(" target=").append(target)
        append(" wasmtime=").append(wasmtimeVersion ?: "n/a")
        append(" profile=").append(aotCompatibilityProfileId ?: "n/a")
    }
}

/**
 * Produces consistent artifact diagnostics for CLI and Gradle adapters.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
object WasmlineArtifactDiagnostics {
    /** Describes one output using its unified build record. */
    fun describe(artifact: WasmlineCompiledArtifact, record: WasmlineAotBuildRecord): WasmlineArtifactDiagnostic {
        val profile = artifact.aotCompatibilityProfileId?.let { profileId ->
            record.resolvedProfiles.single { it.id == profileId }
        }
        return WasmlineArtifactDiagnostic(
            artifact = artifact.contentRelativePath,
            format = artifact.format,
            executionModel = record.runtimeContract.executionModel,
            artifactBackend = artifact.artifactBackend,
            target = artifact.normalizedTarget,
            wasmtimeVersion = profile?.wasmtimeVersion,
            aotCompatibilityProfileId = profile?.id,
        )
    }

    /** Formats one output using its unified build record. */
    fun format(artifact: WasmlineCompiledArtifact, record: WasmlineAotBuildRecord): String = describe(artifact, record).render()
}
