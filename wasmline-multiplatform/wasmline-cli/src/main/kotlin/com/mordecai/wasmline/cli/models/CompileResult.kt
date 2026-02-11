package com.mordecai.wasmline.cli.models

import crow.mordecai.wasmline.model.WasmlineArtifact
import kotlinx.serialization.Serializable

/**
 * Intermediate compile output written to compile-result.json.
 * Consumed by the [com.mordecai.wasmline.cli.Manifest] command to build manifest.wlm.
 *
 * 2026/2/12
 * @author crowforkotlin
 */
@Serializable
data class CompileResult(
    val wasmtimeVersion: String,
    val inputFile: String,
    val artifacts: List<WasmlineArtifact>
)