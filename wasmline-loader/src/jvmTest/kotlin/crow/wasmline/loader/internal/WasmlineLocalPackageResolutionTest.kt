@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline.loader.internal

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineLoadStage
import crow.wasmline.WasmlineLoadState
import crow.wasmline.extensions.Keys
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.loader.WasmlineLoadOptions
import crow.wasmline.loader.WasmlineLoadRequest
import crow.wasmline.loader.WasmlineSource
import crow.wasmline.loader.WasmlineSourceResolution
import crow.wasmline.loader.WasmlineTrustedKeySet
import crow.wasmline.loader.internal.crypto.Ed25519
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineArtifactTarget
import crow.wasmline.loader.model.WasmlineArtifactVariant
import crow.wasmline.loader.model.WasmlineManifest
import crow.wasmline.loader.model.WasmlineManifestLimits
import crow.wasmline.loader.model.WasmlineManifestProtocol
import crow.wasmline.loader.model.WasmlineManifestWireFormat
import crow.wasmline.loader.model.WasmlineRuntimeContract
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies local content-addressed package resolution and signed payload handling.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
class WasmlineLocalPackageResolutionTest {
    private val privateKey = Keys.PRIVATE_KEY_1.decodeHex()
    private val publicKey = Keys.PUBLIC_KEY_1.decodeHex()

    @Test
    fun resolvesOnlyTheSelectedContentAddressedFile() = withPackageDirectory { root ->
        val artifactBytes = byteArrayOf(1, 2, 3)
        val digest = artifactBytes.toByteString().sha256().hex()
        val manifest = rawManifest(digest, artifactBytes.size.toLong())
        val manifestFile = File(root, "manifest.wlm").apply { writeBytes(signAndEncode(manifest)) }
        val artifact = File(
            root,
            WasmlineManifestProtocol.artifactRelativePath(digest, WasmlineArtifactFormat.RAW_WASM),
        ).apply {
            parentFile.mkdirs()
            writeBytes(artifactBytes)
        }
        val source = WasmlineSource.LocalManifestPath(manifestFile.absolutePath)

        val resolution = WasmlineLocalPackageResolution.resolve(
            source = source,
            request = WasmlineLoadRequest(
                source = source,
                options = WasmlineLoadOptions(trustedKeys = trustedKeys()),
            ),
            host = browserHost(),
        )

        val continuation = assertIs<WasmlineSourceResolution.ContinueWith>(resolution)
        val selected = assertIs<WasmlineSource.LocalArtifactPath>(continuation.source)
        val selectedDescriptor = assertNotNull(selected.descriptor)
        assertEquals(artifact.canonicalPath, File(selectedDescriptor.path).canonicalPath)
        assertEquals(WasmlineArtifactFormat.RAW_WASM, selectedDescriptor.artifactFormat)
    }

    @Test
    fun rejectsLegacyEnvelopeAsUnsupportedFormat() {
        val envelope = SignedManifestEnvelope(signature = ByteArray(64))

        val rejection = assertIs<WasmlineManifestVerification.Rejected>(
            WasmlinePackageSignatureVerifier.verify(
                envelope = envelope,
                trustedKeys = trustedKeys(),
                packageLocation = "legacy.wlm",
                limits = WasmlineManifestLimits(),
            ),
        )

        assertEquals(WasmlineErrorCode.MANIFEST_FORMAT_UNSUPPORTED, rejection.code)
    }

    @Test
    fun verifiesExactPayloadBeforeDecodingAndRejectsTampering() {
        val envelope = sign(rawManifest(DIGEST, 3))
        assertIs<WasmlineManifestVerification.Verified>(
            WasmlinePackageSignatureVerifier.verify(
                envelope,
                trustedKeys(),
                "signed.wlm",
                WasmlineManifestLimits(),
            ),
        )

        val tampered = envelope.copy(
            payload = envelope.payload.copyOf().also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            },
        )
        val rejection = assertIs<WasmlineManifestVerification.Rejected>(
            WasmlinePackageSignatureVerifier.verify(
                tampered,
                trustedKeys(),
                "signed.wlm",
                WasmlineManifestLimits(),
            ),
        )
        assertEquals(WasmlineErrorCode.SIGNATURE_VERIFICATION_FAILED, rejection.code)
    }

    @Test
    fun rejectsValidSignatureOverNonCanonicalPayloadEncoding() {
        val manifest = rawManifest(DIGEST, 3).copy(
            metadata = linkedMapOf("z" to "last", "a" to "first"),
        )
        val payload = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), manifest)
        val rejection = assertIs<WasmlineManifestVerification.Rejected>(
            WasmlinePackageSignatureVerifier.verify(
                signPayload(payload),
                trustedKeys(),
                "non-canonical.wlm",
                WasmlineManifestLimits(),
            ),
        )

        assertEquals(WasmlineErrorCode.MANIFEST_INVALID, rejection.code)
        assertTrue(rejection.cause.contains("canonical"))
    }

    @Test
    fun rejectsSignedPayloadContainingRetiredArtifactsField() {
        val canonical = WasmlineManifestProtocol.canonicalize(rawManifest(DIGEST, 3))
        val payload = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), canonical) +
            byteArrayOf(RETIRED_ARTIFACTS_FIELD_TAG, 0)
        val rejection = assertIs<WasmlineManifestVerification.Rejected>(
            WasmlinePackageSignatureVerifier.verify(
                signPayload(payload),
                trustedKeys(),
                "retired-field.wlm",
                WasmlineManifestLimits(),
            ),
        )

        assertEquals(WasmlineErrorCode.MANIFEST_INVALID, rejection.code)
        assertTrue(rejection.cause.contains("payload encoding is not canonical"))
    }

    @Test
    fun rejectsSignedPayloadContainingRetiredMinimumField() {
        val payload = canonicalPayload()
        assertIs<WasmlineManifestVerification.Verified>(
            WasmlinePackageSignatureVerifier.verify(
                signPayload(payload),
                trustedKeys(),
                "canonical.wlm",
                WasmlineManifestLimits(),
            ),
        )
        val rejection = assertIs<WasmlineManifestVerification.Rejected>(
            WasmlinePackageSignatureVerifier.verify(
                signPayload(withRetiredMinimumField(payload)),
                trustedKeys(),
                "retired-minimum-field.wlm",
                WasmlineManifestLimits(),
            ),
        )

        assertEquals(WasmlineLoadStage.MANIFEST_DECODING, rejection.stage)
        assertEquals(WasmlineErrorCode.MANIFEST_INVALID, rejection.code)
        assertTrue(rejection.cause.contains("payload encoding is not canonical"))
    }

    @Test
    fun rejectsLocalManifestContainingRetiredMinimumField() = withPackageDirectory { root ->
        val payload = withRetiredMinimumField(canonicalPayload())
        val manifestFile = File(root, "manifest.wlm").apply {
            writeBytes(ProtoBuf.encodeToByteArray(SignedManifestEnvelope.serializer(), signPayload(payload)))
        }
        val source = WasmlineSource.LocalManifestPath(manifestFile.absolutePath)

        val resolution = WasmlineLocalPackageResolution.resolve(
            source,
            WasmlineLoadRequest(source, options = WasmlineLoadOptions(trustedKeys = trustedKeys())),
            browserHost(),
        )

        val complete = assertIs<WasmlineSourceResolution.Complete>(resolution)
        val failure = assertIs<WasmlineLoadState.Failure>(complete.state)
        assertEquals(WasmlineLoadStage.MANIFEST_DECODING, failure.failure.stage)
        assertEquals(WasmlineErrorCode.MANIFEST_INVALID, failure.failure.code)
        assertTrue(failure.failure.message.contains("payload encoding is not canonical"))
    }

    @Test
    fun rejectsLocalArtifactWithWrongSizeWithoutLoadingIt() = withPackageDirectory { root ->
        val manifest = rawManifest(DIGEST, 3)
        val manifestFile = File(root, "manifest.wlm").apply { writeBytes(signAndEncode(manifest)) }
        File(root, WasmlineManifestProtocol.artifactRelativePath(DIGEST, WasmlineArtifactFormat.RAW_WASM)).apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1, 2))
        }
        val source = WasmlineSource.LocalManifestPath(manifestFile.absolutePath)

        val resolution = WasmlineLocalPackageResolution.resolve(
            source,
            WasmlineLoadRequest(source, options = WasmlineLoadOptions(trustedKeys = trustedKeys())),
            browserHost(),
        )

        val complete = assertIs<WasmlineSourceResolution.Complete>(resolution)
        val failure = assertIs<WasmlineLoadState.Failure>(complete.state)
        assertEquals(WasmlineErrorCode.ARTIFACT_INTEGRITY_FAILED, failure.failure.code)
        assertTrue(failure.failure.message.contains("size"))
    }

    @Test
    fun rejectsOversizedLocalManifestBeforeDecoding() = withPackageDirectory { root ->
        val manifestFile = File(root, "manifest.wlm").apply { writeBytes(ByteArray(256)) }
        val source = WasmlineSource.LocalManifestPath(manifestFile.absolutePath)
        val limits = WasmlineManifestLimits(maxManifestBytes = 64, maxPayloadBytes = 64)

        val resolution = WasmlineLocalPackageResolution.resolve(
            source,
            WasmlineLoadRequest(
                source,
                options = WasmlineLoadOptions(trustedKeys = trustedKeys(), manifestLimits = limits),
            ),
            browserHost(),
        )

        val complete = assertIs<WasmlineSourceResolution.Complete>(resolution)
        val failure = assertIs<WasmlineLoadState.Failure>(complete.state)
        assertEquals(WasmlineErrorCode.MANIFEST_INVALID, failure.failure.code)
        assertTrue(failure.failure.message.contains("byte limit"))
    }

    private fun canonicalPayload(manifest: WasmlineManifest = rawManifest(DIGEST, 3)): ByteArray = ProtoBuf.encodeToByteArray(
        WasmlineManifest.serializer(),
        WasmlineManifestProtocol.canonicalize(manifest),
    )

    /**
     * Inserts the retired manifest field 4 between the top-level field 3 and the next field.
     *
     * The canonical fixture encodes field 1 and field 2 as single-byte-length strings followed by
     * field 3 as a varint, so the insertion point is resolved by walking those top-level fields only.
     */
    private fun withRetiredMinimumField(payload: ByteArray): ByteArray {
        var offset = 0
        for (field in 1..2) {
            assertEquals((field shl 3) or WIRE_TYPE_LENGTH_DELIMITED, payload[offset++].toInt() and 0xFF)
            val length = payload[offset++].toInt() and 0xFF
            assertTrue(length < 0x80, "Fixture string fields must encode their length in one varint byte.")
            offset += length
        }
        assertEquals((3 shl 3) or WIRE_TYPE_VARINT, payload[offset++].toInt() and 0xFF)
        while ((payload[offset++].toInt() and VARINT_CONTINUATION_BIT) != 0) {
            // Skip the remaining field 3 varint bytes.
        }
        return payload.copyOfRange(0, offset) + RETIRED_MINIMUM_FIELD + payload.copyOfRange(offset, payload.size)
    }

    private fun rawManifest(digest: String, sizeBytes: Long): WasmlineManifest = WasmlineManifest(
        pluginId = "crow.wasmline.local",
        version = "1.0.0",
        versionCode = 1,
        buildTimestamp = 0,
        runtimeContract = WasmlineRuntimeContract(
            WasmlineExecutionModel.CORE_WASM,
            WasmlineInvocationProtocol.WASMLINE_SERVICE,
        ),
        artifactTargets = listOf(
            WasmlineArtifactTarget(
                format = WasmlineArtifactFormat.RAW_WASM,
                architecture = "wasm32",
                pointerWidth = 32,
                variants = listOf(WasmlineArtifactVariant(sha256 = digest, sizeBytes = sizeBytes)),
            ),
        ),
    )

    private fun signAndEncode(manifest: WasmlineManifest): ByteArray =
        ProtoBuf.encodeToByteArray(SignedManifestEnvelope.serializer(), sign(manifest))

    private fun sign(manifest: WasmlineManifest): SignedManifestEnvelope = signPayload(canonicalPayload(manifest))

    private fun signPayload(payload: ByteArray): SignedManifestEnvelope {
        val version = WasmlineManifestWireFormat.CURRENT_FORMAT_VERSION
        return SignedManifestEnvelope(
            signature = Ed25519.sign(
                WasmlineManifestProtocol.signingMessage(version, payload).toByteString(),
                privateKey,
            ).toByteArray(),
            formatVersion = version,
            payload = payload,
        )
    }

    private fun trustedKeys(): WasmlineTrustedKeySet = WasmlineTrustedKeySet.Builder()
        .add("Ed25519", keyId = null, publicKey = publicKey.toByteArray())
        .build()

    private fun browserHost(): WasmlineHostArtifactTarget = WasmlineHostArtifactTarget(
        operatingSystem = "browser",
        architecture = "wasm32",
        pointerWidth = 32,
        supportedArtifactFormats = setOf(WasmlineArtifactFormat.RAW_WASM),
    )

    private companion object {
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val RETIRED_ARTIFACTS_FIELD_TAG: Byte = 0x62
        const val WIRE_TYPE_VARINT: Int = 0
        const val WIRE_TYPE_LENGTH_DELIMITED: Int = 2
        const val VARINT_CONTINUATION_BIT: Int = 0x80

        /** Encodes retired manifest field 4 as the length-delimited value `1.0.0`. */
        val RETIRED_MINIMUM_FIELD: ByteArray = byteArrayOf(0x22, 0x05, 0x31, 0x2e, 0x30, 0x2e, 0x30)
    }
}

private inline fun withPackageDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-local-package-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
