package crow.wasmline.cli

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import crow.wasmline.RawExportKind
import crow.wasmline.RawValueType
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies that the CLI exposes only catalog-backed AOT compilation options.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
class CoreCliRegressionTest {
    @Test
    fun `compile rejects removed local Wasmtime directory options`() = withCoreCliDirectory { root ->
        val input = File(root, "plugin.wasm").apply { writeBytes(byteArrayOf(0, 97, 115, 109)) }

        listOf("--wasmtime", "--arch", "--compiler-executable").forEach { removedOption ->
            assertFailsWith<UsageError> {
                Compile().parse(
                    listOf(
                        "--input",
                        input.absolutePath,
                        removedOption,
                        "unused",
                    ),
                )
            }
        }
    }

    @Test
    fun `invocation options load canonical raw ABI metadata`() = withCoreCliDirectory { root ->
        val rawAbiFile = File(root, "raw-abi.json").apply {
            writeText(
                """
                {
                  "version": 1,
                  "exports": [
                    {
                      "name": "add_i32",
                      "kind": "FUNCTION",
                      "signature": {
                        "parameters": ["I32", "I32"],
                        "results": ["I32"]
                      }
                    }
                  ],
                  "imports": [],
                  "memoryExport": "memory",
                  "requiredFeatures": []
                }
                """.trimIndent(),
            )
        }

        val invocation = parseInvocationOptions(
            executionModelName = WasmlineExecutionModel.CORE_WASM.name,
            invocationProtocolName = WasmlineInvocationProtocol.RAW_EXPORT.name,
            exportName = "add_i32",
            contractMetadataEntries = emptyList(),
            rawAbiMetadataFile = rawAbiFile,
        )

        val export = requireNotNull(invocation.rawAbi).exports.single()
        assertEquals(RawExportKind.FUNCTION, export.kind)
        assertEquals(listOf(RawValueType.I32, RawValueType.I32), export.signature?.parameters)
        assertEquals(listOf(RawValueType.I32), export.signature?.results)
    }
}

private inline fun withCoreCliDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-cli-core-regression-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
