@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline

import crow.wasmline.extensions.Keys
import crow.wasmline.loader.internal.crypto.Ed25519
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineAotCompatibilityProfile
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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the signed manifest wire format, canonical form, and validation rules.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
class ManifestTest {
    private val privateKey = Keys.PRIVATE_KEY_1.decodeHex()
    private val publicKey = Keys.PUBLIC_KEY_1.decodeHex()

    @Test
    fun protobufRoundTripPreservesEnvelopeByteArrayContent() {
        val envelope = sign(canonicalManifest())
        val encoded = ProtoBuf.encodeToByteArray(SignedManifestEnvelope.serializer(), envelope)
        val decoded = ProtoBuf.decodeFromByteArray(SignedManifestEnvelope.serializer(), encoded)

        assertEquals(envelope, decoded)
        assertContentEquals(envelope.signature, decoded.signature)
        assertContentEquals(envelope.payload, decoded.payload)
        assertEquals(envelope.hashCode(), decoded.hashCode())
    }

    @Test
    fun signatureCoversDomainFormatVersionAndExactPayload() {
        val envelope = sign(canonicalManifest())

        assertTrue(verify(envelope))
        assertFalse(verify(envelope.copy(formatVersion = envelope.formatVersion + 1)))
        val tamperedPayload = envelope.payload.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        assertFalse(verify(envelope.copy(payload = tamperedPayload)))
        val tamperedSignature = envelope.signature.copyOf().also {
            it[0] = (it[0].toInt() xor 1).toByte()
        }
        assertFalse(verify(envelope.copy(signature = tamperedSignature)))
    }

    @Test
    fun canonicalizationProducesStableBytesAndMergesEqualContent() {
        val first = manifest(
            metadata = linkedMapOf("z" to "last", "a" to "first"),
            targets = listOf(
                pulleyTarget(
                    listOf(
                        variant(PULLEY_PROFILE_ID, SHARED_DIGEST),
                        variant(SECOND_PULLEY_PROFILE_ID, SHARED_DIGEST),
                    ),
                ),
                rawTarget(),
            ),
            profiles = listOf(profile(SECOND_PULLEY_PROFILE_ID), profile(PULLEY_PROFILE_ID)),
        )
        val second = manifest(
            metadata = linkedMapOf("a" to "first", "z" to "last"),
            targets = listOf(
                rawTarget(),
                pulleyTarget(
                    listOf(
                        WasmlineArtifactVariant(
                            listOf(SECOND_PULLEY_PROFILE_ID, PULLEY_PROFILE_ID),
                            SHARED_DIGEST,
                            ARTIFACT_SIZE,
                        ),
                    ),
                ),
            ),
            profiles = listOf(profile(PULLEY_PROFILE_ID), profile(SECOND_PULLEY_PROFILE_ID)),
        )

        val canonicalFirst = WasmlineManifestProtocol.canonicalize(first)
        val canonicalSecond = WasmlineManifestProtocol.canonicalize(second)
        val firstBytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), canonicalFirst)
        val secondBytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), canonicalSecond)

        assertEquals(canonicalFirst, canonicalSecond)
        assertContentEquals(firstBytes, secondBytes)
        assertEquals(1, canonicalFirst.artifactTargets.single { it.format == WasmlineArtifactFormat.PWASM }.variants.size)
        assertNull(WasmlineManifestProtocol.validationError(canonicalFirst))
    }

    @Test
    fun rejectsNonCanonicalMapIterationOrder() {
        val manifest = manifest(
            metadata = linkedMapOf("z" to "last", "a" to "first"),
            targets = listOf(pulleyTarget(listOf(variant(PULLEY_PROFILE_ID, SHARED_DIGEST)))),
            profiles = listOf(profile(PULLEY_PROFILE_ID)),
        )

        assertTrue(WasmlineManifestProtocol.validationError(manifest).orEmpty().contains("canonical ordering"))
    }

    @Test
    fun fieldNumberRegistryNeverReusesRetiredFields() {
        assertTrue(
            WasmlineManifestWireFormat.envelopeFieldNumbers
                .intersect(WasmlineManifestWireFormat.retiredEnvelopeFieldNumbers)
                .isEmpty(),
        )
        assertTrue(
            WasmlineManifestWireFormat.manifestFieldNumbers
                .intersect(WasmlineManifestWireFormat.retiredManifestFieldNumbers)
                .isEmpty(),
        )
        assertEquals(setOf(4), WasmlineManifestWireFormat.retiredEnvelopeFieldNumbers)
        assertEquals(setOf(12), WasmlineManifestWireFormat.retiredManifestFieldNumbers)
        assertEquals((1..5).toSet(), WasmlineManifestWireFormat.runtimeContractFieldNumbers)
        assertEquals((1..5).toSet(), WasmlineManifestWireFormat.aotCompatibilityProfileFieldNumbers)
        assertEquals((1..6).toSet(), WasmlineManifestWireFormat.artifactTargetFieldNumbers)
        assertEquals((1..3).toSet(), WasmlineManifestWireFormat.artifactVariantFieldNumbers)
        assertEquals((1..5).toSet(), WasmlineManifestWireFormat.rawAbiMetadataFieldNumbers)
        assertEquals((1..3).toSet(), WasmlineManifestWireFormat.rawExportFieldNumbers)
        assertEquals((1..3).toSet(), WasmlineManifestWireFormat.rawImportDeclarationFieldNumbers)
        assertEquals((1..2).toSet(), WasmlineManifestWireFormat.rawFunctionSignatureFieldNumbers)
    }

    @Test
    fun contentAddressedPathsAreFormatSpecificAndTraversalFree() {
        assertEquals(
            "artifacts/sha256/aa/$SHARED_DIGEST.cwasm",
            WasmlineManifestProtocol.artifactRelativePath(SHARED_DIGEST, WasmlineArtifactFormat.CWASM),
        )
        assertEquals(
            "artifacts/sha256/aa/$SHARED_DIGEST.pwasm",
            WasmlineManifestProtocol.artifactRelativePath(SHARED_DIGEST, WasmlineArtifactFormat.PWASM),
        )
        assertNotEquals(
            WasmlineManifestProtocol.artifactRelativePath(SHARED_DIGEST, WasmlineArtifactFormat.CWASM),
            WasmlineManifestProtocol.artifactRelativePath(SHARED_DIGEST, WasmlineArtifactFormat.PWASM),
        )
    }

    @Test
    fun rejectsDuplicateUnknownAndCrossBackendProfileReferences() {
        val duplicateProfile = canonicalManifest().let { value ->
            value.copy(aotCompatibilityProfiles = value.aotCompatibilityProfiles + value.aotCompatibilityProfiles.single())
        }
        assertValidationContains(duplicateProfile, "duplicate AOT compatibility profile")

        val unknownReference = canonicalManifest().copy(
            artifactTargets = listOf(pulleyTarget(listOf(variant(UNKNOWN_PROFILE_ID, SHARED_DIGEST)))),
        )
        assertValidationContains(unknownReference, "references unknown profile")

        val crossBackend = canonicalManifest().copy(
            aotCompatibilityProfiles = listOf(profile(CRANELIFT_PROFILE_ID, WasmlineEngineKind.CRANELIFT)),
            artifactTargets = listOf(pulleyTarget(listOf(variant(CRANELIFT_PROFILE_ID, SHARED_DIGEST)))),
        )
        assertValidationContains(crossBackend, "references CRANELIFT profile")
    }

    @Test
    fun rejectsInvalidRawAndAotVariantShapes() {
        val rawWithProfile = canonicalManifest().copy(
            artifactTargets = listOf(
                rawTarget().copy(variants = listOf(variant(PULLEY_PROFILE_ID, RAW_DIGEST))),
            ),
        )
        assertValidationContains(rawWithProfile, "RAW_WASM artifact variants must not reference")

        val aotWithoutProfile = canonicalManifest().copy(
            artifactTargets = listOf(pulleyTarget(listOf(WasmlineArtifactVariant(emptyList(), SHARED_DIGEST, ARTIFACT_SIZE)))),
        )
        assertValidationContains(aotWithoutProfile, "must reference at least one")

        val zeroSize = canonicalManifest().copy(
            artifactTargets = listOf(pulleyTarget(listOf(variant(PULLEY_PROFILE_ID, SHARED_DIGEST).copy(sizeBytes = 0)))),
        )
        assertValidationContains(zeroSize, "sizeBytes must be positive")

        val duplicateTarget = canonicalManifest().let { value ->
            value.copy(artifactTargets = value.artifactTargets + value.artifactTargets.single())
        }
        assertValidationContains(duplicateTarget, "duplicate artifact target")
    }

    @Test
    fun rejectsComponentRawWasmAndInvalidProtocolPairs() {
        val componentRaw = manifest(
            contract = WasmlineRuntimeContract(
                WasmlineExecutionModel.COMPONENT_MODEL,
                WasmlineInvocationProtocol.COMPONENT_EXPORT,
            ),
            targets = listOf(rawTarget()),
            profiles = emptyList(),
        )
        assertValidationContains(componentRaw, "Published RAW_WASM targets require")

        val componentRawExport = componentRaw.copy(
            runtimeContract = WasmlineRuntimeContract(
                WasmlineExecutionModel.COMPONENT_MODEL,
                WasmlineInvocationProtocol.RAW_EXPORT,
            ),
            artifactTargets = listOf(pulleyTarget(listOf(variant(PULLEY_PROFILE_ID, SHARED_DIGEST)))),
            aotCompatibilityProfiles = listOf(profile(PULLEY_PROFILE_ID)),
        )
        assertValidationContains(componentRawExport, "COMPONENT_MODEL cannot use RAW_EXPORT")
    }

    @Test
    fun enforcesEnvelopeAndCollectionLimits() {
        val envelope = sign(canonicalManifest())
        assertTrue(
            WasmlineManifestProtocol.envelopeValidationError(
                envelope,
                WasmlineManifestLimits(maxManifestBytes = envelope.payload.size, maxPayloadBytes = envelope.payload.size - 1),
            ).orEmpty().contains("payload exceeds"),
        )
        val tooManyTargets = canonicalManifest().let { value ->
            value.copy(
                artifactTargets = listOf(
                    value.artifactTargets.single(),
                    rawTarget(),
                ),
            )
        }
        assertTrue(
            WasmlineManifestProtocol.validationError(
                tooManyTargets,
                WasmlineManifestLimits(maxTargets = 1),
                requireCanonicalOrder = false,
            ).orEmpty().contains("artifact targets"),
        )
    }

    @Test
    fun enforcesRawAbiDeclarationSignatureAndStringLimits() {
        val rawAbi = RawAbiMetadata(
            exports = listOf(
                RawExport("first", RawExportKind.FUNCTION, RawFunctionSignature()),
                RawExport(
                    "second",
                    RawExportKind.FUNCTION,
                    RawFunctionSignature(parameters = listOf(RawValueType.I32, RawValueType.I64)),
                ),
            ),
        )
        val manifest = canonicalManifest().copy(
            runtimeContract = WasmlineRuntimeContract(
                WasmlineExecutionModel.CORE_WASM,
                WasmlineInvocationProtocol.RAW_EXPORT,
                rawAbi = rawAbi,
            ),
        )

        assertTrue(
            WasmlineManifestProtocol.validationError(
                manifest,
                WasmlineManifestLimits(maxRawAbiExports = 1),
                requireCanonicalOrder = false,
            ).orEmpty().contains("more than 1 exports"),
        )
        assertTrue(
            WasmlineManifestProtocol.validationError(
                manifest,
                WasmlineManifestLimits(maxRawFunctionParameters = 1),
                requireCanonicalOrder = false,
            ).orEmpty().contains("more than 1 parameters"),
        )
        assertTrue(
            WasmlineManifestProtocol.validationError(
                manifest.copy(
                    runtimeContract = manifest.runtimeContract.copy(
                        rawAbi = rawAbi.copy(
                            exports = rawAbi.exports.mapIndexed { index, export ->
                                if (index == 0) export.copy(name = "x".repeat(129)) else export
                            },
                        ),
                    ),
                ),
                WasmlineManifestLimits(maxStringBytes = 128),
                requireCanonicalOrder = false,
            ).orEmpty().contains("rawAbi.exports.name"),
        )
    }

    @Test
    fun validatesDistributionIdentityAndTargetStringLimits() {
        val manifest = canonicalManifest()
        val profile = manifest.aotCompatibilityProfiles.single()
        val mismatchedDistribution = manifest.copy(
            aotCompatibilityProfiles = listOf(profile.copy(wasmtimeDistributionVersion = "12.3.5.1")),
        )
        assertValidationContains(mismatchedDistribution, "must extend Wasmtime version")

        val threeSegmentDistribution = manifest.copy(
            aotCompatibilityProfiles = listOf(profile.copy(wasmtimeDistributionVersion = "12.3.4")),
        )
        assertValidationContains(threeSegmentDistribution, "expected x.y.z.d")

        val zeroDistributionRevision = manifest.copy(
            aotCompatibilityProfiles = listOf(profile.copy(wasmtimeDistributionVersion = "12.3.4.0")),
        )
        assertValidationContains(zeroDistributionRevision, "expected x.y.z.d")

        val oversizedArchitecture = manifest.copy(
            artifactTargets = listOf(manifest.artifactTargets.single().copy(architecture = "x".repeat(129))),
        )
        assertTrue(
            WasmlineManifestProtocol.validationError(
                oversizedArchitecture,
                WasmlineManifestLimits(maxStringBytes = 128),
                requireCanonicalOrder = false,
            ).orEmpty().contains("artifactTargets.architecture"),
        )
    }

    private fun sign(manifest: WasmlineManifest): SignedManifestEnvelope {
        val payload = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), manifest)
        val formatVersion = WasmlineManifestWireFormat.CURRENT_FORMAT_VERSION
        val signature = Ed25519.sign(
            WasmlineManifestProtocol.signingMessage(formatVersion, payload).toByteString(),
            privateKey,
        )
        return SignedManifestEnvelope(
            signature = signature.toByteArray(),
            formatVersion = formatVersion,
            payload = payload,
        )
    }

    private fun verify(envelope: SignedManifestEnvelope): Boolean = Ed25519.verify(
        WasmlineManifestProtocol.signingMessage(envelope.formatVersion, envelope.payload).toByteString(),
        envelope.signature.toByteString(),
        publicKey,
    )

    private fun canonicalManifest(): WasmlineManifest = WasmlineManifestProtocol.canonicalize(
        manifest(
            targets = listOf(pulleyTarget(listOf(variant(PULLEY_PROFILE_ID, SHARED_DIGEST)))),
            profiles = listOf(profile(PULLEY_PROFILE_ID)),
        ),
    )

    private fun manifest(
        metadata: Map<String, String> = emptyMap(),
        contract: WasmlineRuntimeContract = WasmlineRuntimeContract(
            WasmlineExecutionModel.CORE_WASM,
            WasmlineInvocationProtocol.WASMLINE_SERVICE,
        ),
        targets: List<WasmlineArtifactTarget>,
        profiles: List<WasmlineAotCompatibilityProfile>,
    ): WasmlineManifest = WasmlineManifest(
        pluginId = "crow.wasmline.test",
        version = "12.3.4",
        versionCode = 1,
        minSdkVersion = "12.3.4",
        buildTimestamp = 1_700_000_000_000,
        metadata = metadata,
        runtimeContract = contract,
        aotCompatibilityProfiles = profiles,
        artifactTargets = targets,
    )

    private fun profile(id: String, backend: WasmlineEngineKind = WasmlineEngineKind.PULLEY): WasmlineAotCompatibilityProfile =
        WasmlineAotCompatibilityProfile(
            id = id,
            artifactBackend = backend,
            wasmtimeVersion = "12.3.4",
            wasmtimeDistributionVersion = "12.3.4.1",
            compileProfileSchemaVersion = 1,
        )

    private fun pulleyTarget(variants: List<WasmlineArtifactVariant>): WasmlineArtifactTarget = WasmlineArtifactTarget(
        format = WasmlineArtifactFormat.PWASM,
        architecture = "pulley64",
        pointerWidth = 64,
        variants = variants,
    )

    private fun rawTarget(): WasmlineArtifactTarget = WasmlineArtifactTarget(
        format = WasmlineArtifactFormat.RAW_WASM,
        architecture = "wasm32",
        pointerWidth = 32,
        variants = listOf(WasmlineArtifactVariant(sha256 = RAW_DIGEST, sizeBytes = ARTIFACT_SIZE)),
    )

    private fun variant(profileId: String, digest: String): WasmlineArtifactVariant =
        WasmlineArtifactVariant(listOf(profileId), digest, ARTIFACT_SIZE)

    private fun assertValidationContains(manifest: WasmlineManifest, expected: String) {
        assertTrue(
            WasmlineManifestProtocol.validationError(manifest, requireCanonicalOrder = false).orEmpty().contains(expected),
            "Expected validation error containing '$expected'.",
        )
    }

    /**
     * Defines immutable manifest fixture identities.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    private companion object {
        const val ARTIFACT_SIZE = 3L
        const val SHARED_DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val RAW_DIGEST = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val PULLEY_PROFILE_ID = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
        const val SECOND_PULLEY_PROFILE_ID = "sha256:2222222222222222222222222222222222222222222222222222222222222222"
        const val CRANELIFT_PROFILE_ID = "sha256:3333333333333333333333333333333333333333333333333333333333333333"
        const val UNKNOWN_PROFILE_ID = "sha256:4444444444444444444444444444444444444444444444444444444444444444"
    }
}
