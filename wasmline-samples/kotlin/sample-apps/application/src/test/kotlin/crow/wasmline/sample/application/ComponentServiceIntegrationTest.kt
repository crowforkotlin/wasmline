package crow.wasmline.sample.application

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineComponentServiceContract
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineLoadResult
import crow.wasmline.bind
import crow.wasmline.link
import crow.wasmline.loader.WasmlineLoader
import crow.wasmline.loader.WasmlineLoadOptions
import crow.wasmline.sample.component.ComponentEchoRequest
import crow.wasmline.sample.component.ComponentHostService
import crow.wasmline.sample.component.ComponentPluginService
import crow.wasmline.serialization.WasmlineSerializationConfig
import crow.wasmline.wasmlineNativeRuntimeInfo
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
    fun bootstrap() {
        WasmlineLoader.bootstrap()
    }

    @AfterTest
    fun shutdown() {
        WasmlineLoader.shutdown()
    }

    @Test
    fun cwasmUsesGeneratedLinkBindInBothDirections() = runTest {
        verifyGeneratedServiceRoundTrip(
            artifact = File(requireNotNull(System.getProperty(CWASM_PROPERTY))),
            format = WasmlineArtifactFormat.CWASM,
        )
    }

    @Test
    fun pwasmUsesGeneratedLinkBindInBothDirections() = runTest {
        verifyGeneratedServiceRoundTrip(
            artifact = File(requireNotNull(System.getProperty(PWASM_PROPERTY))),
            format = WasmlineArtifactFormat.PWASM,
        )
    }

    @Test
    fun initializationAndRouterStateAreInstanceScoped() = runTest {
        val artifact = File(requireNotNull(System.getProperty(CWASM_PROPERTY)))
        val first = loadComponent(artifact, WasmlineArtifactFormat.CWASM)
        val second = loadComponent(artifact, WasmlineArtifactFormat.CWASM)
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

    private suspend fun verifyGeneratedServiceRoundTrip(artifact: File, format: WasmlineArtifactFormat) {
        assertTrue(artifact.isFile, "Missing Wasmline Service fixture: ${artifact.absolutePath}")
        val plugin = loadComponent(artifact, format)
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

    private suspend fun loadComponent(artifact: File, format: WasmlineArtifactFormat): Wasmline {
        val runtime = requireNotNull(wasmlineNativeRuntimeInfo())
        val descriptor = WasmlineArtifactDescriptor(
            path = artifact.absolutePath,
            artifactFormat = format,
            targetCpu = if (format == WasmlineArtifactFormat.PWASM) "pulley64" else runtime.targetCpu,
            targetOs = if (format == WasmlineArtifactFormat.PWASM) null else runtime.targetOs,
            targetCompilerVersion = "wasmtime-${runtime.wasmtimeVersion}",
            is64Bit = true,
            executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
            invocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
            exportName = WasmlineComponentServiceContract.DEFAULT_EXPORT,
            contractMetadata = mapOf(
                WasmlineComponentServiceContract.METADATA_PROFILE to WasmlineComponentServiceContract.PROFILE,
                WasmlineComponentServiceContract.METADATA_WIT_PACKAGE to WasmlineComponentServiceContract.WIT_PACKAGE,
                WasmlineComponentServiceContract.METADATA_CODEC to WasmlineComponentServiceContract.DEFAULT_CODEC,
                WasmlineComponentServiceContract.METADATA_VERSION to WasmlineComponentServiceContract.VERSION,
            ),
        )
        val result = WasmlineLoader.load(
            descriptor = descriptor,
            options = WasmlineLoadOptions(
                runtimeConfig = WasmlineConfig(serialization = WasmlineSerializationConfig.protobuf()),
            ),
        )
        return (result as? WasmlineLoadResult.Success)?.wasmline
            ?: error("Unable to load Wasmline Service fixture: $result")
    }

    private companion object {
        const val CWASM_PROPERTY = "wasmline.test.componentService.cwasm"
        const val PWASM_PROPERTY = "wasmline.test.componentService.pwasm"
    }
}
