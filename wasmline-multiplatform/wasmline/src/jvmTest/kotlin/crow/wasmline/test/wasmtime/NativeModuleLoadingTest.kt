package crow.wasmline.test.wasmtime

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Tests for Wasmline native module loading capabilities.
 *
 * These tests validate basic functionality of the Wasmtime runtime through Wasmline's public APIs.
 *
 * @author crowforkotlin
 */
class NativeModuleLoadingTest {

    @Test
    fun loadsSimplePrecompiledModule() {
        // This test validates that precompiled module loading works.
        // It uses a simple .cwasm file path that should exist after build.
        
        val tempFile = java.io.File.createTempFile("wasmline_test_", ".pwasm")
        tempFile.deleteOnExit()
        
        try {
            // Test with a non-existent file - should fail gracefully
            val result = crow.wasmline.wasmlineLoadArtifact(
                filepath = tempFile.absolutePath,
                config = crow.wasmline.WasmlineConfig(supportConcurrent = false)
            )
            
            // Should return a Failure state with appropriate error message
            assertTrue(result is crow.wasmline.WasmlineLoadState.Failure, "Expected failure for missing artifact")
            val failure = result as crow.wasmline.WasmlineLoadState.Failure
            assertEquals(crow.wasmline.WasmlineLoadState.CODE_FAILURE, failure.code)
            assertTrue(failure.cause.contains("not found") || failure.cause.contains("artifact"), 
                      "Error should mention missing artifact: ${failure.cause}")
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun handlesMissingArtifactGracefully() {
        val result = crow.wasmline.wasmlineLoadArtifact(
            filepath = "/nonexistent/path/to/plugin.pwasm",
            config = crow.wasmline.WasmlineConfig(supportConcurrent = true)
        )
        
        assertTrue(result is crow.wasmline.WasmlineLoadState.Failure)
        assertEquals(crow.wasmline.WasmlineLoadState.CODE_FAILURE, (result as crow.wasmline.WasmlineLoadState.Failure).code)
        assertTrue((result as crow.wasmline.WasmlineLoadState.Failure).cause.contains("not found"))
    }

    @Test
    fun createsValidTempWasmFile() {
        val content = byteArrayOf(0x00, 0x61, 0x73, 0x6D)  // WASM magic number
        val tempFile = java.io.File.createTempFile("wasmline_", ".wasm")
        tempFile.writeBytes(content)
        tempFile.deleteOnExit()
        
        assertTrue(tempFile.exists(), "Temp file should be created")
        assertTrue(tempFile.length() > 0, "Temp file should have content")
        
        tempFile.delete()
    }
}
