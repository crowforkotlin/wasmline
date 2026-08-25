package crow.wasmline

import kotlinx.serialization.Serializable

/**
 * Describes a binary artifact and its invocation boundary.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 *
 * @property path Artifact path or Web cache key.
 * @property artifactFormat Physical binary format, when known.
 * @property targetCpu Native artifact CPU target, when applicable.
 * @property targetOs Native artifact operating-system target, when applicable.
 * @property targetCompilerVersion Compiler/runtime compatibility marker.
 * @property is64Bit Native artifact bitness marker.
 * @property executionModel Runtime execution model.
 * @property invocationProtocol Host invocation protocol.
 * @property exportName Optional selected export name for protocol adapters.
 * @property contractMetadata Additional contract metadata.
 * @property rawAbi Optional versioned scalar Core Wasm ABI metadata used by RAW_EXPORT.
 */
@Serializable
data class WasmlineArtifactDescriptor(
    val path: String,
    val artifactFormat: WasmlineArtifactFormat? = null,
    val targetCpu: String? = null,
    val targetOs: String? = null,
    val targetCompilerVersion: String? = null,
    val is64Bit: Boolean? = null,
    val executionModel: WasmlineExecutionModel = WasmlineExecutionModel.CORE_WASM,
    val invocationProtocol: WasmlineInvocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
    val exportName: String? = null,
    val contractMetadata: Map<String, String> = emptyMap(),
    val rawAbi: RawAbiMetadata? = null,
) {
    /** Validates the descriptor against the execution model and invocation protocol. */
    fun validationError(): String? {
        if (path.isBlank()) return "Artifact path must not be blank."
        if (exportName != null && exportName.isBlank()) return "Artifact exportName must not be blank when present."
        if (rawAbi != null && invocationProtocol != WasmlineInvocationProtocol.RAW_EXPORT) {
            return "rawAbi metadata requires the RAW_EXPORT invocation protocol."
        }
        if (rawAbi != null && rawAbi.version > RawAbiMetadata.CURRENT_VERSION) {
            return "Unsupported rawAbi metadata version ${rawAbi.version}."
        }
        if (
            executionModel == WasmlineExecutionModel.COMPONENT_MODEL &&
            invocationProtocol == WasmlineInvocationProtocol.WASMLINE_SERVICE &&
            exportName != null &&
            exportName != WasmlineComponentServiceContract.DEFAULT_EXPORT
        ) {
            return "WASMLINE_SERVICE Component exportName must be '${WasmlineComponentServiceContract.DEFAULT_EXPORT}'."
        }
        return when (executionModel) {
            WasmlineExecutionModel.CORE_WASM -> when (invocationProtocol) {
                WasmlineInvocationProtocol.WASMLINE_SERVICE,
                WasmlineInvocationProtocol.RAW_EXPORT,
                -> null

                WasmlineInvocationProtocol.COMPONENT_EXPORT ->
                    "COMPONENT_EXPORT requires COMPONENT_MODEL."
            }

            WasmlineExecutionModel.COMPONENT_MODEL -> when (invocationProtocol) {
                WasmlineInvocationProtocol.COMPONENT_EXPORT,
                WasmlineInvocationProtocol.WASMLINE_SERVICE,
                -> null

                WasmlineInvocationProtocol.RAW_EXPORT ->
                    "COMPONENT_MODEL cannot use RAW_EXPORT."
            }
        }
    }
}
