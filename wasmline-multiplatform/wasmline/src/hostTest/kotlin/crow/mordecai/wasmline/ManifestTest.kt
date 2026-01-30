package crow.mordecai.wasmline

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
            buildTimestamp = System.currentTimeMillis(),

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

        // 2. Deployment Phase: Serialize and Sign
        val payloadBytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), manifest)

        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(payloadBytes)
        val signatureBytes = signer.sign()

        // 3. Wrap in Envelope
        val envelope = SignedManifestEnvelope(
            payload = payloadBytes,
            signature = signatureBytes
        )
        val finalOutputBytes = ProtoBuf.encodeToByteArray(SignedManifestEnvelope.serializer(), envelope)

        println("Manifest Protobuf Size: ${payloadBytes.size} bytes")
        println("Final Envelope Size: ${finalOutputBytes.size} bytes")

        // 4. Runtime Phase: Host Verification
        val receivedEnvelope = ProtoBuf.Default.decodeFromByteArray(SignedManifestEnvelope.serializer(), finalOutputBytes)

        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey) // Verify using the MATCHING public key
        verifier.update(receivedEnvelope.payload)
        val isVerified = verifier.verify(receivedEnvelope.signature)

        // 5. Assertions
        println("Signature Valid: $isVerified")
        assertTrue(isVerified, "Signature should be valid for correct key pair")

        val runtimeManifest = ProtoBuf.Default.decodeFromByteArray(WasmlineManifest.serializer(), receivedEnvelope.payload)
        assertEquals(manifest.pluginId, runtimeManifest.pluginId)
    }

    @Test
    fun `test signature verification failure with key mismatch`() {
        printHeader("Test: Signature Failure (Key Mismatch)")

        val manifest = createTestManifest()
        val payloadBytes = ProtoBuf.Default.encodeToByteArray(WasmlineManifest.serializer(), manifest)

        // 1. Generate KeyPair A (The Authorized Publisher)
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val keyPairPublisher = kpg.generateKeyPair()

        // 2. Generate KeyPair B (The Host or Attacker with wrong keys)
        val keyPairWrong = kpg.generateKeyPair()

        println("Publisher Public Key HashCode : ${keyPairPublisher.public.hashCode()}")
        println("Wrong Public Key HashCode:     ${keyPairWrong.public.hashCode()}")

        // 3. Sign using Publisher's Private Key (A)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPairPublisher.private)
        signer.update(payloadBytes)
        val signatureBytes = signer.sign()

        // 4. Wrap in Envelope
        val envelope = SignedManifestEnvelope(
            payload = payloadBytes,
            signature = signatureBytes
        )
        val finalOutputBytes = ProtoBuf.Default.encodeToByteArray(SignedManifestEnvelope.serializer(), envelope)

        // 5. Simulate Host loading the file
        val receivedEnvelope = ProtoBuf.Default.decodeFromByteArray(SignedManifestEnvelope.serializer(), finalOutputBytes)

        // 6. Verify using the WRONG Public Key (B)
        // Scenario: Host has an old or different public key than the one used to sign.
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(keyPairWrong.public)
        verifier.update(receivedEnvelope.payload)
        val isVerified = verifier.verify(receivedEnvelope.signature)

        // 7. Assertions
        println("Verification Result with wrong key: $isVerified")
        assertFalse(isVerified, "Signature verification MUST fail when public key does not match private key")
    }
}