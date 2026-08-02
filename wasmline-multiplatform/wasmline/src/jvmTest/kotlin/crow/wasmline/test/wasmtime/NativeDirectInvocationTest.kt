/**
 * Tests direct Raw Core and Component Model export calls.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
package crow.wasmline.test.wasmtime

import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineComponentCallResult
import crow.wasmline.WasmlineComponentValue
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineLoadState
import crow.wasmline.WasmlineRawCallResult
import crow.wasmline.WasmlineRawValue
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invokeComponentResult
import crow.wasmline.invokeRawResult
import crow.wasmline.wasmlineLoadArtifact
import crow.wasmline.wasmlineShutdown
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NativeDirectInvocationTest {

    @Test
    fun rawExportReturnsValuesAndRecoverableFailures() {
        val artifact = createRawFixture()
        try {
            val handle = loadArtifact(
                WasmlineArtifactDescriptor(
                    path = artifact.absolutePath,
                    executionModel = WasmlineExecutionModel.CORE_WASM,
                    invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
                    exportName = "add",
                ),
            )
            try {
                val success = assertIs<WasmlineCallResult.Success<WasmlineRawCallResult>>(
                    handle.invokeRawResult(
                        exportName = "add",
                        arguments = listOf(WasmlineRawValue.I32(2), WasmlineRawValue.I32(3)),
                    ),
                )
                assertEquals(listOf(WasmlineRawValue.I32(5)), success.value.values)

                val typeFailure = assertIs<WasmlineCallResult.Failure>(
                    handle.invokeRawResult(
                        exportName = "add",
                        arguments = listOf(WasmlineRawValue.I64(2), WasmlineRawValue.I64(3)),
                    ),
                )
                assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, typeFailure.error.code)

                val missingFailure = assertIs<WasmlineCallResult.Failure>(
                    handle.invokeRawResult(exportName = "missing"),
                )
                assertEquals(WasmlineErrorCode.CORE_EXPORT_NOT_FOUND, missingFailure.error.code)

                val trapFailure = assertIs<WasmlineCallResult.Failure>(
                    handle.invokeRawResult(exportName = "trap"),
                )
                assertEquals(WasmlineErrorCode.CORE_TRAP, trapFailure.error.code)
            } finally {
                handle.close()
            }
        } finally {
            wasmlineShutdown()
            artifact.delete()
        }
    }

    @Test
    fun componentExportLoadsWithoutWitAndConvertsValues() {
        val artifact = copyComponentFixture()
        try {
            val handle = loadArtifact(
                WasmlineArtifactDescriptor(
                    path = artifact.absolutePath,
                    executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
                    invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
                    exportName = "add",
                ),
            )
            try {
                val success = assertIs<WasmlineCallResult.Success<WasmlineComponentCallResult>>(
                    handle.invokeComponentResult(
                        exportName = "add",
                        arguments = listOf(WasmlineComponentValue.S32(2), WasmlineComponentValue.S32(3)),
                    ),
                )
                assertEquals(listOf(WasmlineComponentValue.S32(5)), success.value.values)

                val typeFailure = assertIs<WasmlineCallResult.Failure>(
                    handle.invokeComponentResult(
                        exportName = "add",
                        arguments = listOf(WasmlineComponentValue.StringValue("2"), WasmlineComponentValue.S32(3)),
                    ),
                )
                assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, typeFailure.error.code)

                val missingFailure = assertIs<WasmlineCallResult.Failure>(
                    handle.invokeComponentResult(exportName = "missing"),
                )
                assertEquals(WasmlineErrorCode.COMPONENT_EXPORT_NOT_FOUND, missingFailure.error.code)

                val trapFailure = assertIs<WasmlineCallResult.Failure>(
                    handle.invokeComponentResult(exportName = "trap"),
                )
                assertEquals(WasmlineErrorCode.COMPONENT_TRAP, trapFailure.error.code)
            } finally {
                handle.close()
            }
        } finally {
            wasmlineShutdown()
            artifact.delete()
        }
    }

    private fun loadArtifact(descriptor: WasmlineArtifactDescriptor): crow.wasmline.Wasmline {
        val state = wasmlineLoadArtifact(
            descriptor = descriptor,
            config = WasmlineConfig(supportConcurrent = false),
        )
        val success = assertIs<WasmlineLoadState.Success>(state)
        return success.wasmline
    }

    private fun createRawFixture(): File = File.createTempFile("wasmline-raw-export-", ".wasm").apply {
        writeBytes(
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
                0x01, 0x0B, 0x02, 0x60, 0x02, 0x7F, 0x7F, 0x01, 0x7F, 0x60, 0x00, 0x01, 0x7F,
                0x03, 0x03, 0x02, 0x00, 0x01,
                0x07, 0x0E, 0x02, 0x03, 0x61, 0x64, 0x64, 0x00, 0x00, 0x04, 0x74, 0x72, 0x61, 0x70, 0x00, 0x01,
                0x0A, 0x0D, 0x02, 0x07, 0x00, 0x20, 0x00, 0x20, 0x01, 0x6A, 0x0B, 0x03, 0x00, 0x00, 0x0B,
            ),
        )
        deleteOnExit()
    }

    private fun copyComponentFixture(): File {
        val destination = File.createTempFile("wasmline-component-export-", ".wasm")
        NativeDirectInvocationTest::class.java.getResourceAsStream("/fixtures/component-export.wasm").use { input ->
            requireNotNull(input) { "Component fixture resource is missing." }
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        destination.deleteOnExit()
        return destination
    }
}
