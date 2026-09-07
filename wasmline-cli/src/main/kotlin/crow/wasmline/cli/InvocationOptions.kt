package crow.wasmline.cli

import crow.wasmline.RawAbiMetadata
import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.plugin.core.aot.WasmlineRawAbiMetadataCodec
import java.io.File

/**
 * Parses invocation metadata used by CLI manifest commands.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
internal data class InvocationOptions(
    val executionModel: WasmlineExecutionModel,
    val invocationProtocol: WasmlineInvocationProtocol,
    val exportName: String?,
    val contractMetadata: Map<String, String>,
    val rawAbi: RawAbiMetadata?,
)

internal fun parseInvocationOptions(
    executionModelName: String,
    invocationProtocolName: String,
    exportName: String?,
    contractMetadataEntries: Collection<String>,
    rawAbiMetadataFile: File? = null,
): InvocationOptions {
    val executionModel = parseEnum<WasmlineExecutionModel>(executionModelName, "execution model")
    val invocationProtocol = parseEnum<WasmlineInvocationProtocol>(invocationProtocolName, "invocation protocol")
    val contractMetadata = contractMetadataEntries.associate { entry ->
        val separator = entry.indexOf('=')
        require(separator > 0) { "Contract metadata must use key=value: $entry" }
        entry.substring(0, separator) to entry.substring(separator + 1)
    }
    val rawAbi = rawAbiMetadataFile?.let(WasmlineRawAbiMetadataCodec::read)
    val descriptorError = WasmlineArtifactDescriptor(
        path = "cli",
        executionModel = executionModel,
        invocationProtocol = invocationProtocol,
        exportName = exportName,
        contractMetadata = contractMetadata,
        rawAbi = rawAbi,
    ).validationError()
    require(descriptorError == null) { "Invalid invocation descriptor: $descriptorError" }
    return InvocationOptions(executionModel, invocationProtocol, exportName, contractMetadata, rawAbi)
}

internal fun <T> componentServiceValue(protocol: WasmlineInvocationProtocol, value: T): T? =
    value.takeIf { protocol == WasmlineInvocationProtocol.WASMLINE_SERVICE }

private inline fun <reified T : Enum<T>> parseEnum(value: String, label: String): T =
    enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: error("Unknown $label '$value'.")
