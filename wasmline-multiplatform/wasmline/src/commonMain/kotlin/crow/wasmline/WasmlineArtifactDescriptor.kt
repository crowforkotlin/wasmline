/**
 * Describes a binary artifact and its invocation boundary.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

package crow.wasmline

import kotlinx.serialization.Serializable

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
) {
    fun validationError(): String? {
        if (path.isBlank()) return "Artifact path must not be blank."
        if (invocationProtocol == WasmlineInvocationProtocol.RAW_EXPORT && exportName.isNullOrBlank()) {
            return "An exportName is required for direct export invocation."
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
