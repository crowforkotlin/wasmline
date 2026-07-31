package crow.wasmline.plugin.core.compiler

import crow.wasmline.loader.model.WasmlineArtifact
import kotlinx.serialization.Serializable

/**
 * Stores the output of a Wasmtime compilation.
 *
 * The manifest command reads this file to create a signed manifest.
 *
 * 2026/7/31
 * @author crowforkotlin
 */
@Serializable
data class CompileResult(
    val wasmtimeVersion: String,
    val inputFile: String,
    val artifacts: List<WasmlineArtifact>
)
