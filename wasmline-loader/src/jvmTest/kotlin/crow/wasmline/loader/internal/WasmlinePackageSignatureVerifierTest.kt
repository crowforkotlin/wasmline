@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package crow.wasmline.loader.internal

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.extensions.Keys
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
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Verifies that the signed input covers the envelope key identifier.
 *
 * Date: 2026-11-11
 * Author: crowforkotlin
 */
class WasmlinePackageSignatureVerifierTest {
    private val privateKey = Keys.PRIVATE_KEY_1.decodeHex()
    private val publicKey = Keys.PUBLIC_KEY_1.decodeHex()

    @Test
    fun `verifies an envelope whose key id matches the signed input`() {
        val envelope = signedEnvelope(publicKeyId = "release-2026")

        assertIs<WasmlineManifestVerification.Verified>(verify(envelope))
    }

    @Test
    fun `rejects an envelope whose key id was changed after signing`() {
        val signature = signedEnvelope(publicKeyId = "release-2026")
        val relabeled = signature.copy(publicKeyId = "untrusted-third-party")

        val rejected = assertIs<WasmlineManifestVerification.Rejected>(verify(relabeled))

        assertEquals("Manifest signature verification failed for package 'inline'.", rejected.cause)
    }

    @Test
    fun `rejects dropping the key id after signing`() {
        val signature = signedEnvelope(publicKeyId = "release-2026")

        assertIs<WasmlineManifestVerification.Rejected>(verify(signature.copy(publicKeyId = null)))
    }

    @Test
    fun `wildcard trust does not rescue a relabeled envelope`() {
        val signature = signedEnvelope(publicKeyId = "release-2026")
        val relabeled = signature.copy(publicKeyId = "other")
        val keys = WasmlineTrustedKeySet.Builder()
            .add("Ed25519", "release-2026", publicKey.toByteArray())
            .add("Ed25519", null, publicKey.toByteArray())
            .build()

        assertIs<WasmlineManifestVerification.Rejected>(verify(relabeled, keys))
    }

    private fun verify(
        envelope: SignedManifestEnvelope,
        keys: WasmlineTrustedKeySet = WasmlineTrustedKeySet.Builder()
            .add("Ed25519", null, publicKey.toByteArray())
            .build(),
    ): WasmlineManifestVerification = WasmlinePackageSignatureVerifier.verify(
        envelope = envelope,
        trustedKeys = keys,
        packageLocation = "inline",
        limits = WasmlineManifestLimits(),
    )

    private fun signedEnvelope(publicKeyId: String?): SignedManifestEnvelope {
        val manifest = WasmlineManifestProtocol.canonicalize(
            WasmlineManifest(
                pluginId = "crow.wasmline.signature",
                version = "1.0.0",
                versionCode = 1,
                buildTimestamp = 0,
                runtimeContract = WasmlineRuntimeContract(
                    WasmlineExecutionModel.CORE_WASM,
                    WasmlineInvocationProtocol.RAW_EXPORT,
                ),
                artifactTargets = listOf(
                    WasmlineArtifactTarget(
                        format = WasmlineArtifactFormat.RAW_WASM,
                        architecture = "wasm32",
                        pointerWidth = 32,
                        variants = listOf(
                            WasmlineArtifactVariant(sha256 = "00".repeat(32), sizeBytes = 3),
                        ),
                    ),
                ),
            ),
        )
        val payload = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), manifest)
        val version = WasmlineManifestWireFormat.CURRENT_FORMAT_VERSION
        return SignedManifestEnvelope(
            signature = Ed25519.sign(
                WasmlineManifestProtocol.signingMessage(version, publicKeyId, payload).toByteString(),
                privateKey,
            ).toByteArray(),
            publicKeyId = publicKeyId,
            formatVersion = version,
            payload = payload,
        )
    }
}
