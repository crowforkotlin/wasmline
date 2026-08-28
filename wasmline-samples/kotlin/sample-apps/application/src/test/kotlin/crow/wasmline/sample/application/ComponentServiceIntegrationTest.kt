package crow.wasmline.sample.application

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineLoadResult
import crow.wasmline.WasmlineRuntime
import crow.wasmline.bind
import crow.wasmline.link
import crow.wasmline.loader.WasmlineLoader
import crow.wasmline.loader.WasmlineLoadOptions
import crow.wasmline.loader.WasmlineTrustedKeySet
import crow.wasmline.sample.component.ComponentEchoRequest
import crow.wasmline.sample.component.ComponentHostService
import crow.wasmline.sample.component.ComponentPluginService
import crow.wasmline.serialization.WasmlineSerializationConfig
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComponentServiceIntegrationTest {
    @BeforeTest
    fun preloadRuntime() {
        WasmlineRuntime.preload()
    }

    @AfterTest
    fun shutdown() {
        WasmlineRuntime.shutdown()
    }

    @Test
    fun manifestSelectsCompatibleArtifactForGeneratedLinkBind() = runTest {
        verifyGeneratedServiceRoundTrip(manifestFile())
    }

    @Test
    fun initializationAndRouterStateAreInstanceScoped() = runTest {
        val manifest = manifestFile()
        val first = loadComponent(manifest)
        val second = loadComponent(manifest)
        try {
            val firstService = first.link<ComponentPluginService>()
            val secondService = second.link<ComponentPluginService>()

            assertEquals(1, firstService.initializationCount())
            assertEquals(1, firstService.initializationCount())
            assertEquals(1, secondService.initializationCount())
        } finally {
            first.close()
            second.close()
        }
    }

    private suspend fun verifyGeneratedServiceRoundTrip(manifest: File) {
        assertTrue(manifest.isFile, "Missing Wasmline Service manifest: ${manifest.absolutePath}")
        val plugin = loadComponent(manifest)
        try {
            var callbacks = 0
            plugin.bind(
                object : ComponentHostService {
                    override fun callback(payload: ByteArray): ByteArray {
                        callbacks += 1
                        return payload + byteArrayOf(9)
                    }
                },
            )

            val service = plugin.link<ComponentPluginService>()
            assertEquals("plugin:hello", service.echo(ComponentEchoRequest("hello")).value)
            assertContentEquals(byteArrayOf(1, 2, 9), service.callback(byteArrayOf(1, 2)))
            assertEquals(1, callbacks)
            assertContentEquals(ByteArray(0), service.empty())
            assertEquals(1, service.initializationCount())
        } finally {
            plugin.close()
        }
    }

    private suspend fun loadComponent(manifest: File): Wasmline {
        val result = WasmlineLoader.load(
            source = manifest.absolutePath,
            options = WasmlineLoadOptions(
                runtimeConfig = WasmlineConfig(serialization = WasmlineSerializationConfig.protobuf()),
                trustedKeys = trustedKeys,
            ),
        )
        return (result as? WasmlineLoadResult.Success)?.wasmline
            ?: error("Unable to load Wasmline Service fixture: $result")
    }

    private fun manifestFile(): File = File(requireNotNull(System.getProperty(MANIFEST_PROPERTY)))

    private companion object {
        const val MANIFEST_PROPERTY = "wasmline.test.componentService.manifest"
        val trustedKeys = WasmlineTrustedKeySet.Builder()
            .addHex(
                algorithm = "Ed25519",
                keyId = null,
                publicKeyHex = "5a778289bee0c57b05a1c48c8ef312da6ce8e4e4f13fc1a2e8e5aa4cde7ae0db",
            )
            .build()
    }
}
