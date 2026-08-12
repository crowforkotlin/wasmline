/**
 * Parses invocation metadata used by CLI manifest commands.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
package crow.wasmline.cli

import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol

internal data class InvocationOptions(
    val executionModel: WasmlineExecutionModel,
    val invocationProtocol: WasmlineInvocationProtocol,
    val exportName: String?,
    val contractMetadata: Map<String, String>,
)

internal fun parseInvocationOptions(
    executionModelName: String,
    invocationProtocolName: String,
    exportName: String?,
    contractMetadataEntries: Collection<String>,
): InvocationOptions {
    val executionModel = parseEnum<WasmlineExecutionModel>(executionModelName, "execution model")
    val invocationProtocol = parseEnum<WasmlineInvocationProtocol>(invocationProtocolName, "invocation protocol")
    val contractMetadata = contractMetadataEntries.associate { entry ->
        val separator = entry.indexOf('=')
        require(separator > 0) { "Contract metadata must use key=value: $entry" }
        entry.substring(0, separator) to entry.substring(separator + 1)
    }
    val descriptorError = WasmlineArtifactDescriptor(
        path = "cli",
        executionModel = executionModel,
        invocationProtocol = invocationProtocol,
        exportName = exportName,
        contractMetadata = contractMetadata,
    ).validationError()
    require(descriptorError == null) { "Invalid invocation descriptor: $descriptorError" }
    return InvocationOptions(executionModel, invocationProtocol, exportName, contractMetadata)
}

internal fun <T> componentServiceValue(protocol: WasmlineInvocationProtocol, value: T): T? =
    value.takeIf { protocol == WasmlineInvocationProtocol.WASMLINE_SERVICE }

private inline fun <reified T : Enum<T>> parseEnum(value: String, label: String): T =
    enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: error("Unknown $label '$value'.")
