package crow.wasmline.test.wasmtime

import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineLoadResult
import crow.wasmline.WasmlineWarmupMode
import crow.wasmline.link
import crow.wasmline.loader.WasmlineLoader
import crow.wasmline.wasmlineBootstrap
import crow.wasmline.wasmlineShutdown
import crow.wasmline.wasmlineWarmup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end tests for EchoService validating string round-trip communication.
 *
 * 2026-07-30
 * @author crowforkotlin
 */
class NativeEchoServiceTest {

    companion object {
        private val ARTIFACT_PATH = requireNotNull(System.getProperty("wasmline.plugin.artifact.path")) {
            "Missing wasmline plugin artifact path."
        }
    }

    /**
     * Tests echoing simple strings.
     */
    @Test
    fun echoesSimpleString() {
        val artifactPath = findArtifact()

        wasmlineBootstrap()
        wasmlineWarmup(WasmlineWarmupMode.CRANELIFT)
        try {
            val wasmline = assertIs<WasmlineLoadResult.Success>(
                WasmlineLoader.load(artifactPath, WasmlineConfig(supportConcurrent = false)),
            ).wasmline
            try {
                val echoService = wasmline.link<crow.wasmline.test.plugin.EchoService>()
                assertEquals("Hello, World!", echoService.echo("Hello, World!"))
                assertEquals("Wasmline", echoService.echo("Wasmline"))
            } finally {
                wasmline.close()
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
        wasmlineWarmup(WasmlineWarmupMode.CRANELIFT)
        try {
            val wasmline = assertIs<WasmlineLoadResult.Success>(
                WasmlineLoader.load(artifactPath, WasmlineConfig(supportConcurrent = false)),
            ).wasmline
            try {
                val echoService = wasmline.link<crow.wasmline.test.plugin.EchoService>()
                assertEquals("Prefix: Test Message", echoService.echoWithPrefix("Prefix: ", "Test Message"))
                assertEquals("[INFO] Log entry", echoService.echoWithPrefix("[INFO] ", "Log entry"))
            } finally {
                wasmline.close()
            }
        } finally {
            wasmlineShutdown()
        }
    }

    private fun findArtifact(): String {
        val path = ARTIFACT_PATH
        assertTrue(java.io.File(path).exists(), "Artifact not found: $path. Run './gradlew jvmTest' first.")
        return path
    }
}
