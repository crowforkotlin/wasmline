package crow.wasmline.test.wasmtime

import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineLoadState
import crow.wasmline.loader.WasmlineLoader
import crow.wasmline.wasmlineBootstrap
import crow.wasmline.wasmlineShutdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests for EchoService validating string round-trip communication.
 *
 * 2026-07-30
 * @author crowforkotlin
 */
class NativeEchoServiceTest {

    companion object {
        private val PWASM_PATH = "${System.getProperty("user.dir")}/../wasmline-test-plugin/build/wasmline/output/wasmline-test-plugin-1.0.0/wasmline-test-plugin-pulley64.pwasm"
    }

    /**
     * Tests echoing simple strings.
     */
    @Test
    fun echoesSimpleString() {
        val artifactPath = findArtifact()
        
        wasmlineBootstrap()
        try {
            val state = WasmlineLoader.loadArtifact(artifactPath, WasmlineConfig(supportConcurrent = false))
            val loader = (state as WasmlineLoadState.Loaded).loader
            
            loader.load<crow.wasmline.test.plugin.EchoService>().use { echoService ->
                assertEquals("Hello, World!", echoService.echo("Hello, World!"))
                assertEquals("Wasmline", echoService.echo("Wasmline"))
            }
        } finally {
            wasmlineShutdown()
        }
    }

    /**
     * Tests echoing with prefix.
     */
    @Test
    fun echoesWithPrefix() {
        val artifactPath = findArtifact()
        
        wasmlineBootstrap()
        try {
            val state = WasmlineLoader.loadArtifact(artifactPath, WasmlineConfig(supportConcurrent = false))
            val loader = (state as WasmlineLoadState.Loaded).loader
            
            loader.load<crow.wasmline.test.plugin.EchoService>().use { echoService ->
                assertEquals("Prefix: Test Message", echoService.echoWithPrefix("Prefix: ", "Test Message"))
                assertEquals("[INFO] Log entry", echoService.echoWithPrefix("[INFO] ", "Log entry"))
            }
        } finally {
            wasmlineShutdown()
        }
    }

    private fun findArtifact(): String {
        val path = PWASM_PATH
        assertTrue(java.io.File(path).exists(), "Artifact not found: $path. Run build first.")
        return path
    }
}
