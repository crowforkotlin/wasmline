@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline.loader.internal

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineLoadState
import crow.wasmline.extensions.Keys
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.loader.VerifiedPackageArtifact
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
        val selected = assertIs<VerifiedPackageArtifact>(continuation.source)
        assertEquals(artifact.canonicalPath, File(selected.descriptor.path).canonicalPath)
        assertEquals(WasmlineArtifactFormat.RAW_WASM, selected.descriptor.artifactFormat)
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
    fun rejectsSignedPayloadContainingRetiredManifestField() {
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

    private fun rawManifest(digest: String, sizeBytes: Long): WasmlineManifest = WasmlineManifest(
        pluginId = "crow.wasmline.local",
        version = "1.0.0",
        versionCode = 1,
        minSdkVersion = "1.0.0",
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

    private fun sign(manifest: WasmlineManifest): SignedManifestEnvelope {
        val canonical = WasmlineManifestProtocol.canonicalize(manifest)
        val payload = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), canonical)
        return signPayload(payload)
    }

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
