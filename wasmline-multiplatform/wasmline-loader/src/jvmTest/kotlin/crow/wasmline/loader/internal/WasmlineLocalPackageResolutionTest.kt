package crow.wasmline.loader.internal

import crow.wasmline.WasmlineComponentRpcContract
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.WasmlineSource
import crow.wasmline.loader.WasmlineSourceResolution
import crow.wasmline.loader.internal.crypto.Ed25519
import crow.wasmline.loader.internal.crypto.newKeyPairFromSeed
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import crow.wasmline.loader.model.WasmlineManifest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Verifies local package artifact selection for browser and native hosts. */
class WasmlineLocalPackageResolutionTest {

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `live Gradle Component manifest has a valid Ed25519 signature`() {
        if (System.getenv(LIVE_TESTS_ENV) != "1") return

        val manifest = requiredLiveFile(COMPONENT_MANIFEST_ENV)
        val privateKey = requiredLiveFile(COMPONENT_PRIVATE_KEY_ENV).readText().trim().decodeHex()
        val envelope = ProtoBuf.decodeFromByteArray(SignedManifestEnvelope.serializer(), manifest.readBytes())
        val manifestBytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), envelope.manifest)
        val keyPair = newKeyPairFromSeed(privateKey)

        assertTrue(
            Ed25519.verify(
                message = manifestBytes.toByteString(),
                signature = envelope.signature.toByteString(),
                publicKey = keyPair.publicKey,
            ),
        )
    }

    @Test
    fun `live CLI Component manifest resolves to a Component descriptor`() {
        if (System.getenv(LIVE_TESTS_ENV) != "1") return

        val manifest = requiredLiveFile(COMPONENT_MANIFEST_ENV)

        val resolution = WasmlineLocalPackageResolution.resolve(
            WasmlineSource.LocalManifestPath(manifest.absolutePath),
        )
        val continuation = assertIs<WasmlineSourceResolution.ContinueWith>(resolution)
        val source = assertIs<WasmlineSource.LocalArtifactPath>(continuation.source)
        assertTrue(File(source.path).isFile)
        val descriptor = requireNotNull(source.descriptor)
        assertEquals(WasmlineExecutionModel.COMPONENT_MODEL, descriptor.executionModel)
        assertEquals(WasmlineInvocationProtocol.COMPONENT_EXPORT, descriptor.invocationProtocol)
        assertEquals(WasmlineComponentRpcContract.DEFAULT_EXPORT, descriptor.exportName)
        assertEquals(
            WasmlineComponentRpcContract.DEFAULT_CODEC,
            descriptor.contractMetadata[WasmlineComponentRpcContract.METADATA_CODEC],
        )
    }

    @Test
    fun `browser host prefers raw wasm artifacts`() {
        val selected = WasmlineLocalPackageResolution.selectArtifact(
            artifacts = listOf(
                WasmlineArtifact(
                    type = WasmlineArtifactType.PWASM,
                    url = "plugin.pwasm",
                    sha256 = "pwasm",
                    targetCpu = "pulley64",
                    is64Bit = true,
                ),
                WasmlineArtifact(
                    type = WasmlineArtifactType.WASM,
                    url = "plugin.wasm",
                    sha256 = "wasm",
                    targetCpu = "wasmjs",
                    targetOs = "browser",
                    is64Bit = true,
                ),
            ),
            target = WasmlineHostArtifactTarget(
                os = "browser",
                cpu = "wasmjs",
                is64Bit = true,
            ),
        )

        assertEquals("plugin.wasm", selected?.url)
    }

    @Test
    fun `native host ignores raw wasm artifacts`() {
        val selected = WasmlineLocalPackageResolution.selectArtifact(
            artifacts = listOf(
                WasmlineArtifact(
                    type = WasmlineArtifactType.WASM,
                    url = "plugin.wasm",
                    sha256 = "wasm",
                    targetCpu = "wasmjs",
                    targetOs = "browser",
                    is64Bit = true,
                ),
                WasmlineArtifact(
                    type = WasmlineArtifactType.PWASM,
                    url = "plugin.pwasm",
                    sha256 = "pwasm",
                    targetCpu = "pulley64",
                    is64Bit = true,
                ),
            ),
            target = WasmlineHostArtifactTarget(
                os = "linux",
                cpu = "x86_64",
                is64Bit = true,
            ),
        )

        assertEquals("plugin.pwasm", selected?.url)
    }

    @Test
    fun `native host accepts a raw Component Wasm artifact`() {
        val selected = WasmlineLocalPackageResolution.selectArtifact(
            artifacts = listOf(
                WasmlineArtifact(
                    type = WasmlineArtifactType.COMPONENT_WASM,
                    url = "plugin.component.wasm",
                    sha256 = "component",
                    executionModel = crow.wasmline.WasmlineExecutionModel.COMPONENT_MODEL,
                    invocationProtocol = crow.wasmline.WasmlineInvocationProtocol.COMPONENT_EXPORT,
                    exportName = "plugin/invoke",
                ),
            ),
            target = WasmlineHostArtifactTarget(
                os = "linux",
                cpu = "x86_64",
                is64Bit = true,
            ),
        )

        assertEquals("plugin.component.wasm", selected?.url)
    }

    @Test
    fun `browser host rejects raw Component Wasm artifacts`() {
        val selected = WasmlineLocalPackageResolution.selectArtifact(
            artifacts = listOf(
                WasmlineArtifact(
                    type = WasmlineArtifactType.COMPONENT_WASM,
                    url = "plugin.component.wasm",
                    sha256 = "component",
                    executionModel = crow.wasmline.WasmlineExecutionModel.COMPONENT_MODEL,
                    invocationProtocol = crow.wasmline.WasmlineInvocationProtocol.COMPONENT_EXPORT,
                    exportName = "plugin/invoke",
                ),
            ),
            target = WasmlineHostArtifactTarget(
                os = "browser",
                cpu = "wasmjs",
                is64Bit = true,
            ),
        )

        assertEquals(null, selected)
    }

    private fun requiredLiveFile(environmentName: String): File {
        val path = requireNotNull(System.getenv(environmentName)) {
            "$environmentName must be set when $LIVE_TESTS_ENV=1."
        }
        return File(path).also { file ->
            require(file.isFile) { "$environmentName does not point to a file: ${file.absolutePath}" }
        }
    }

    private companion object {
        const val LIVE_TESTS_ENV = "WASMLINE_LIVE_TESTS"
        const val COMPONENT_MANIFEST_ENV = "WASMLINE_TEST_COMPONENT_MANIFEST"
        const val COMPONENT_PRIVATE_KEY_ENV = "WASMLINE_TEST_COMPONENT_PRIVATE_KEY"
    }
}
