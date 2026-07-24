package crow.wasmline.cli.models

import crow.wasmline.loader.model.WasmlineArtifact
import kotlinx.serialization.Serializable

/**
 * Intermediate compile output written to compile-result.json.
 * Consumed by the [crow.wasmline.cli.Manifest] command to build manifest.wlm.
 *
 * 2026/2/12
 * @author crowforkotlin
 */
@Serializable
data class CompileResult(val wasmtimeVersion: String, val inputFile: String, val artifacts: List<WasmlineArtifact>)
