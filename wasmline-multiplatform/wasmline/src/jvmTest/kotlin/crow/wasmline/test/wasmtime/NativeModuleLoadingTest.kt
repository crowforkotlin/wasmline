package crow.wasmline.test.wasmtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for Wasmline native module loading capabilities.
 *
 * These tests validate basic functionality of the Wasmtime runtime through Wasmline's public APIs.
 *
 * Author: crowforkotlin
 */
class NativeModuleLoadingTest {

    @Test
    fun reportsMissingPrecompiledModule() {
        val tempFile = java.io.File.createTempFile("wasmline_test_", ".pwasm")
        tempFile.deleteOnExit()
        tempFile.delete()

        try {
            val result = crow.wasmline.wasmlineLoadArtifact(
                filepath = tempFile.absolutePath,
                config = crow.wasmline.WasmlineConfig(supportConcurrent = false),
            )

            assertTrue(result is crow.wasmline.WasmlineLoadState.Failure, "Expected failure for missing artifact")
            val failure = result as crow.wasmline.WasmlineLoadState.Failure
            assertEquals(crow.wasmline.WasmlineLoadState.CODE_FAILURE, failure.code)
            assertTrue(
                failure.cause.contains("not found") || failure.cause.contains("artifact"),
                "Error should mention missing artifact: ${failure.cause}",
            )
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun rejectsMalformedWasmArtifact() {
        val tempFile = java.io.File.createTempFile("wasmline_", ".wasm")
        tempFile.writeBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6D))
        tempFile.deleteOnExit()

        val result = crow.wasmline.wasmlineLoadArtifact(
            filepath = tempFile.absolutePath,
            config = crow.wasmline.WasmlineConfig(supportConcurrent = false),
        )

        assertTrue(result is crow.wasmline.WasmlineLoadState.Failure)
        assertEquals(
            crow.wasmline.WasmlineLoadState.CODE_FAILURE,
            (result as crow.wasmline.WasmlineLoadState.Failure).code,
        )

        tempFile.delete()
    }
}
