package crow.mordecai.wasmline

import com.mordecai.wasmline.loader.internal.crypto.Ed25519
import com.mordecai.wasmline.loader.internal.crypto.Ed25519Constants
import com.mordecai.wasmline.loader.internal.crypto.KeyPair
import com.mordecai.wasmline.loader.internal.crypto.SignatureAlgorithmId
import crow.mordecai.wasmline.extensions.printHeader
import crow.mordecai.wasmline.model.SignedManifestEnvelope
import crow.mordecai.wasmline.model.WasmlineArtifact
import crow.mordecai.wasmline.model.WasmlineArtifactType
import crow.mordecai.wasmline.model.WasmlineManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class ManifestTest {

    // Configure JSON output: Pretty print for readability, ignore unknown keys for backward compatibility
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Helper to create a standard manifest object for testing.
     * Updated to include new display fields.
     */
    private fun createTestManifest(): WasmlineManifest {
        return WasmlineManifest(
            pluginId = "com.mordecai.demo",
            version = "1.0.0",
            versionCode = 100,
            minWasmlineSdkVersion = "0.9.0",
            buildTimestamp = Clock.System.now().toEpochMilliseconds(),

            // New Display Fields
            displayName = "Wasmline Demo Plugin",
            author = "Crow",
            description = "A demo plugin for testing manifest capabilities.",
            iconUrl = "assets/icon.png",
            homePageUrl = "https://github.com/wasmline/demo",

            metadata = mapOf("git_hash" to "ff99aa", "compatibility" to "strict"),
            artifacts = listOf(
                WasmlineArtifact(
                    type = WasmlineArtifactType.CWASM,
                    url = "lib.cwasm",
                    sha256 = "deadbeef12345678",
                    targetCompilerVersion = "wasmtime-17.0",
                    targetCpu = "arm64",
                    targetOs = "android"
                ),
                WasmlineArtifact(
                    type = WasmlineArtifactType.PWASM,
                    url = "lib.pwasm",
                    targetCompilerVersion = "wasmtime-17.0",
                    sha256 = "cafebabe87654321",
                    is64Bit = true
                )
            )
        )
    }

    @Test
    fun `test JSON serialization for debugging`() {
        printHeader("Test: JSON Serialization (Debug View)")

        val manifest = createTestManifest()

        // 1. Serialize to JSON
        val jsonString = json.encodeToString(manifest)
        println("=== Debug JSON (Manifest.json) ===")
        println(jsonString)

        // 2. Deserialize back to Object
        val decodedJson = json.decodeFromString<WasmlineManifest>(jsonString)

        // 3. Verify data integrity
        assertEquals(manifest.pluginId, decodedJson.pluginId)
        assertEquals(manifest.displayName, decodedJson.displayName)
        assertEquals(manifest.iconUrl, decodedJson.iconUrl)
        assertEquals(manifest.artifacts.size, decodedJson.artifacts.size)
        println("JSON serialization and deserialization successful.")
    }

    @Test
    fun `test Protobuf signing and verification is success`() {
        printHeader("Test: Protobuf & Ed25519 Valid Signature")



        // 1. Setup Environment: Generate valid Ed25519 KeyPair
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val keyPair = kpg.generateKeyPair()
        val privateKey = keyPair.private
        val publicKey = keyPair.public

        val manifest = createTestManifest()

        // 2. Signing Phase:
        // We must sign the serialized bytes of the manifest to ensure data integrity.
        val manifestBytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), manifest)

        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(manifestBytes)
        val signatureBytes = signer.sign()

        // 3. Deployment Phase: Wrap into Envelope
        // The Envelope now contains the structured object, not raw bytes.
        val envelope = SignedManifestEnvelope(
            signature = signatureBytes,
            manifest = manifest,
            algorithm = "Ed25519"
        )

        // Final serialization of the entire envelope for storage/transmission
        val finalOutputBytes = ProtoBuf.encodeToByteArray(SignedManifestEnvelope.serializer(), envelope)

        println("Manifest Protobuf Size: ${manifestBytes.size} bytes")
        println("Final Envelope Size: ${finalOutputBytes.size} bytes")

        // 4. Runtime Phase: Host Verification
        // Decode the entire envelope first
        val receivedEnvelope = ProtoBuf.decodeFromByteArray(SignedManifestEnvelope.serializer(), finalOutputBytes)

        // To verify the signature, we need to re-serialize the manifest object back to bytes.
        // Important: Ensure the serializer configuration is identical to the one used during signing.
        val receivedManifestBytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), receivedEnvelope.manifest)

        val verifier = Signature.getInstance(receivedEnvelope.algorithm)
        verifier.initVerify(publicKey)
        verifier.update(receivedManifestBytes)
        val isVerified = verifier.verify(receivedEnvelope.signature)

        // 5. Assertions
        println("Signature Valid: $isVerified")
        assertTrue(isVerified, "Signature must be valid when verified with the matching public key")

        // Verify content integrity
        assertEquals(manifest.pluginId, receivedEnvelope.manifest.pluginId)
        assertEquals(manifest.versionCode, receivedEnvelope.manifest.versionCode)
    }

    @Test
    fun `test signature verification failure with key mismatch`() {
        printHeader("Test: Signature Failure (Key Mismatch)")

        val manifest = createTestManifest()

        // 1. Publisher signs the manifest
        val manifestBytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), manifest)
        val keyPairPublisher = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPairPublisher.private)
        signer.update(manifestBytes)
        val signatureBytes = signer.sign()

        // 2. Wrap into Envelope
        val envelope = SignedManifestEnvelope(
            signature = signatureBytes,
            manifest = manifest
        )
        val envelopeBytes = ProtoBuf.encodeToByteArray(SignedManifestEnvelope.serializer(), envelope)

        // 3. Attacker/Wrong Host attempts verification with a different key
        com.mordecai.wasmline.loader.internal.crypto.generateKeyPair()
        val keyPairWrong = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val receivedEnvelope = ProtoBuf.decodeFromByteArray(SignedManifestEnvelope.serializer(), envelopeBytes)

        // Re-serialize for verification
        val receivedManifestBytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), receivedEnvelope.manifest)

        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(keyPairWrong.public)
        verifier.update(receivedManifestBytes)
        val isVerified = verifier.verify(receivedEnvelope.signature)

        // 4. Assertions
        println("Verification Result with wrong key: $isVerified")
        assertFalse(isVerified, "Signature verification must fail when public key mismatch occurs")
    }
}