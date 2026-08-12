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
    val invocationProtocol: WasmlineInvocationProtocol = WasmlineInvocationProtocol.WASMLINE_CORE,
    val exportName: String? = null,
    val contractMetadata: Map<String, String> = emptyMap(),
) {
    fun validationError(): String? {
        if (path.isBlank()) return "Artifact path must not be blank."
        if (invocationProtocol == WasmlineInvocationProtocol.RAW_EXPORT && exportName.isNullOrBlank()) {
            return "An exportName is required for direct export invocation."
        }
        if (
            invocationProtocol == WasmlineInvocationProtocol.WASMLINE_COMPONENT_RPC &&
            exportName != null &&
            exportName != WasmlineComponentRpcContract.DEFAULT_EXPORT
        ) {
            return "WASMLINE_COMPONENT_RPC exportName must be '${WasmlineComponentRpcContract.DEFAULT_EXPORT}'."
        }
        return when (executionModel) {
            WasmlineExecutionModel.CORE_WASM -> when (invocationProtocol) {
                WasmlineInvocationProtocol.WASMLINE_CORE,
                WasmlineInvocationProtocol.RAW_EXPORT,
                -> null

                WasmlineInvocationProtocol.COMPONENT_EXPORT ->
                    "COMPONENT_EXPORT requires COMPONENT_MODEL."

                WasmlineInvocationProtocol.WASMLINE_COMPONENT_RPC ->
                    "WASMLINE_COMPONENT_RPC requires COMPONENT_MODEL."
            }

            WasmlineExecutionModel.COMPONENT_MODEL -> when (invocationProtocol) {
                WasmlineInvocationProtocol.COMPONENT_EXPORT,
                WasmlineInvocationProtocol.WASMLINE_COMPONENT_RPC,
                -> null

                WasmlineInvocationProtocol.WASMLINE_CORE ->
                    "COMPONENT_MODEL cannot use WASMLINE_CORE."

                WasmlineInvocationProtocol.RAW_EXPORT ->
                    "COMPONENT_MODEL cannot use RAW_EXPORT."
            }
        }
    }
}
