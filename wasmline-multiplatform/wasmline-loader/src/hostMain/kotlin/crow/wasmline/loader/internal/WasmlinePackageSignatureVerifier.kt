@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline.loader.internal

import crow.wasmline.WasmlineLoadStage
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.loader.WasmlineTrustedKeys
import crow.wasmline.loader.internal.crypto.Ed25519
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineManifest
import crow.wasmline.loader.model.WasmlineManifestLimits
import crow.wasmline.loader.model.WasmlineManifestProtocol
import crow.wasmline.loader.model.WasmlineManifestWireFormat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.toByteString

/**
 * Represents manifest verification and payload decoding results.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
internal sealed interface WasmlineManifestVerification {
    /**
     * Contains a verified and strictly validated manifest.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    data class Verified(val manifest: WasmlineManifest) : WasmlineManifestVerification

    /**
     * Contains a stable failure stage and code for rejected input.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    data class Rejected(val cause: String, val stage: WasmlineLoadStage, val code: WasmlineErrorCode) : WasmlineManifestVerification
}

/**
 * Verifies exact envelope payload bytes before decoding package metadata.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
internal object WasmlinePackageSignatureVerifier {
    /** Verifies, decodes, and strictly validates one signed envelope. */
    fun verify(
        envelope: SignedManifestEnvelope,
        trustedKeys: WasmlineTrustedKeys?,
        packageLocation: String,
        limits: WasmlineManifestLimits,
    ): WasmlineManifestVerification {
        if (envelope.formatVersion != WasmlineManifestWireFormat.CURRENT_FORMAT_VERSION) {
            return rejected(
                cause = "Package '$packageLocation' uses unsupported manifest format version ${envelope.formatVersion}.",
                stage = WasmlineLoadStage.MANIFEST_DECODING,
                code = WasmlineErrorCode.MANIFEST_FORMAT_UNSUPPORTED,
            )
        }
        WasmlineManifestProtocol.envelopeValidationError(envelope, limits)?.let { cause ->
            return rejected(
                cause = "Invalid manifest envelope in package '$packageLocation': $cause",
                stage = WasmlineLoadStage.MANIFEST_DECODING,
                code = WasmlineErrorCode.MANIFEST_INVALID,
            )
        }

        val keys = trustedKeys ?: return rejected(
            cause = "Signed package '$packageLocation' requires trustedKeys for manifest signature verification.",
            stage = WasmlineLoadStage.SIGNATURE_VERIFICATION,
            code = WasmlineErrorCode.SIGNATURE_VERIFICATION_FAILED,
        )
        val publicKey = keys.getPublicKey(envelope.algorithm, envelope.publicKeyId)
            ?: return rejected(
                cause = "No trusted key found for algorithm='${envelope.algorithm}', keyId='${envelope.publicKeyId}' " +
                    "in package '$packageLocation'.",
                stage = WasmlineLoadStage.SIGNATURE_VERIFICATION,
                code = WasmlineErrorCode.SIGNATURE_VERIFICATION_FAILED,
            )
        val signingMessage = WasmlineManifestProtocol.signingMessage(envelope.formatVersion, envelope.payload)
        val verified = runCatching {
            Ed25519.verify(
                message = signingMessage.toByteString(),
                signature = envelope.signature.toByteString(),
                publicKey = publicKey.toByteString(),
            )
        }.getOrDefault(false)
        if (!verified) {
            return rejected(
                cause = "Manifest signature verification failed for package '$packageLocation'.",
                stage = WasmlineLoadStage.SIGNATURE_VERIFICATION,
                code = WasmlineErrorCode.SIGNATURE_VERIFICATION_FAILED,
            )
        }

        val manifest = try {
            ProtoBuf.decodeFromByteArray(WasmlineManifest.serializer(), envelope.payload)
        } catch (_: Exception) {
            return rejected(
                cause = "Failed to decode the verified manifest payload from package '$packageLocation'.",
                stage = WasmlineLoadStage.MANIFEST_DECODING,
                code = WasmlineErrorCode.MANIFEST_INVALID,
            )
        }
        WasmlineManifestProtocol.validationError(manifest, limits)?.let { cause ->
            return rejected(
                cause = "Invalid manifest payload in package '$packageLocation': $cause",
                stage = WasmlineLoadStage.MANIFEST_DECODING,
                code = WasmlineErrorCode.MANIFEST_INVALID,
            )
        }
        val canonicalPayload = ProtoBuf.encodeToByteArray(
            WasmlineManifest.serializer(),
            WasmlineManifestProtocol.canonicalize(manifest),
        )
        if (!envelope.payload.contentEquals(canonicalPayload)) {
            return rejected(
                cause = "Invalid manifest payload in package '$packageLocation': payload encoding is not canonical.",
                stage = WasmlineLoadStage.MANIFEST_DECODING,
                code = WasmlineErrorCode.MANIFEST_INVALID,
            )
        }
        return WasmlineManifestVerification.Verified(manifest)
    }

    private fun rejected(cause: String, stage: WasmlineLoadStage, code: WasmlineErrorCode): WasmlineManifestVerification.Rejected =
        WasmlineManifestVerification.Rejected(cause, stage, code)
}
