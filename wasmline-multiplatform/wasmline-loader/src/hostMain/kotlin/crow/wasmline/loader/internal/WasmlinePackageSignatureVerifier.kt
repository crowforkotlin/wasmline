@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline.loader.internal

import crow.wasmline.WasmlineTrustedKeys
import crow.wasmline.loader.internal.crypto.SignatureAlgorithmId
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineManifest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.toByteString

internal sealed interface WasmlineManifestVerification {
    data class Verified(val manifest: WasmlineManifest) : WasmlineManifestVerification

    data class Rejected(val cause: String) : WasmlineManifestVerification
}

/** Verifies the signed envelope before a package resolver reads manifest metadata. */
internal object WasmlinePackageSignatureVerifier {
    fun verify(
        envelope: SignedManifestEnvelope,
        trustedKeys: WasmlineTrustedKeys?,
        packageLocation: String,
    ): WasmlineManifestVerification {
        val keys = trustedKeys ?: return WasmlineManifestVerification.Rejected(
            "Signed package '$packageLocation' requires trustedKeys for manifest signature verification.",
        )

        val algorithmId = try {
            SignatureAlgorithmId.valueOf(envelope.algorithm)
        } catch (_: IllegalArgumentException) {
            return WasmlineManifestVerification.Rejected(
                "Unknown signature algorithm '${envelope.algorithm}' in package '$packageLocation'.",
            )
        }

        val publicKey = keys.getPublicKey(envelope.algorithm, envelope.publicKeyId)
            ?: return WasmlineManifestVerification.Rejected(
                "No trusted key found for algorithm='${envelope.algorithm}', keyId='${envelope.publicKeyId}' " +
                    "in package '$packageLocation'.",
            )

        val manifestBytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), envelope.manifest)
        val verified = algorithmId.get().verify(
            message = manifestBytes.toByteString(),
            signature = envelope.signature.toByteString(),
            publicKey = publicKey.toByteString(),
        )
        return if (verified) {
            WasmlineManifestVerification.Verified(envelope.manifest)
        } else {
            WasmlineManifestVerification.Rejected(
                "Manifest signature verification failed for package '$packageLocation'.",
            )
        }
    }
}
