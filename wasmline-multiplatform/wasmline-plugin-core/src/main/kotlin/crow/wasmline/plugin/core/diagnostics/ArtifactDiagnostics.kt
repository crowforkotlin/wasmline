package crow.wasmline.plugin.core.diagnostics

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType

enum class WasmlineArtifactBackend {
    RAW,
    CRANELIFT,
    PULLEY,
}

data class WasmlineArtifactDiagnostic(
    val artifact: String,
    val format: WasmlineArtifactFormat,
    val executionModel: WasmlineExecutionModel,
    val backend: WasmlineArtifactBackend,
    val target: String,
    val wasmtimeVersion: String?,
) {
    fun render(): String = buildString {
        append("artifact=").append(artifact)
        append(" format=").append(format.name)
        append(" executionModel=").append(executionModel.name)
        append(" backend=").append(backend.name)
        append(" target=").append(target)
        append(" wasmtime=").append(wasmtimeVersion ?: "n/a")
    }
}

/** Produces consistent native artifact diagnostics for CLI and Gradle adapters. */
object WasmlineArtifactDiagnostics {
    fun describe(artifact: WasmlineArtifact): WasmlineArtifactDiagnostic {
        val (format, backend) = when (artifact.type) {
            WasmlineArtifactType.WASM,
            WasmlineArtifactType.COMPONENT_WASM,
            -> WasmlineArtifactFormat.RAW_WASM to WasmlineArtifactBackend.RAW

            WasmlineArtifactType.CWASM -> WasmlineArtifactFormat.CWASM to WasmlineArtifactBackend.CRANELIFT

            WasmlineArtifactType.PWASM -> WasmlineArtifactFormat.PWASM to WasmlineArtifactBackend.PULLEY
        }
        return WasmlineArtifactDiagnostic(
            artifact = artifact.url,
            format = format,
            executionModel = artifact.executionModel,
            backend = backend,
            target = renderTarget(artifact.targetCpu, artifact.targetOs),
            wasmtimeVersion = artifact.targetCompilerVersion
                ?.let(WASMTIME_COMPILER_VERSION::matchEntire)
                ?.groupValues
                ?.get(1),
        )
    }

    fun format(artifact: WasmlineArtifact): String = describe(artifact).render()

    private fun renderTarget(targetCpu: String?, targetOs: String?): String =
        listOfNotNull(targetCpu?.takeIf(String::isNotBlank), targetOs?.takeIf(String::isNotBlank))
            .joinToString("-")
            .ifBlank { "unspecified" }

    private val WASMTIME_COMPILER_VERSION = Regex("wasmtime-([0-9]+\\.[0-9]+\\.[0-9]+)")
}
