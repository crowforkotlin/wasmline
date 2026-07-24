@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline

import crow.wasmline.extensions.Keys
import crow.wasmline.extensions.printHeader
import crow.wasmline.loader.internal.crypto.Ed25519
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import crow.wasmline.loader.model.WasmlineManifest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class ManifestTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val privateKey = Keys.privateKey1.decodeHex()
    private val publicKey = Keys.publicKey1.decodeHex()

    private fun createTestManifest(): WasmlineManifest = WasmlineManifest(
        pluginId = "crow.wasmline.demo",
        version = "1.0.0",
        versionCode = 100,
        minSdkVersion = "0.9.0",
        buildTimestamp = Clock.System.now().toEpochMilliseconds(),
        displayName = "Wasmline Demo Plugin",
        author = "Crow",
        description = "A demo plugin for testing manifest capabilities.",
        iconUrl = "assets/icon.png",
        homePageUrl = "https://github.com/wasmline/demo",
        metadata = mapOf("git_hash" to "ff99aa", "compatibility" to "strict"),
        artifacts = listOf(
            WasmlineArtifact(
                type = WasmlineArtifactType.WASM,
                url = "lib.wasm",
                sha256 = "rawwasm00112233",
                targetCpu = "wasmjs",
                targetOs = "browser",
            ),
            WasmlineArtifact(
                type = WasmlineArtifactType.CWASM,
                url = "lib.cwasm",
                sha256 = "deadbeef12345678",
                targetCompilerVersion = "wasmtime-17.0",
                targetCpu = "arm64",
                targetOs = "android",
            ),
            WasmlineArtifact(
                type = WasmlineArtifactType.PWASM,
                url = "lib.pwasm",
                sha256 = "cafebabe87654321",
                targetCompilerVersion = "wasmtime-17.0",
                is64Bit = true,
            ),
        ),
    )

    private fun signManifest(manifest: WasmlineManifest): SignedManifestEnvelope {
        val manifestBytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), manifest)
        val signature = Ed25519.sign(manifestBytes.toByteString(), privateKey)
        return SignedManifestEnvelope(
            signature = signature.toByteArray(),
            manifest = manifest,
            algorithm = "Ed25519",
        )
    }

    private fun verifyEnvelope(envelope: SignedManifestEnvelope, key: ByteString = publicKey): Boolean {
        val manifestBytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), envelope.manifest)
        return Ed25519.verify(
            manifestBytes.toByteString(),
            envelope.signature.toByteString(),
            key,
        )
    }

    // ==================== Serialization Tests ====================

    @Test
    fun `test JSON manifest round-trip serialization`() {
        printHeader("Test: JSON Manifest Round-Trip")

        val manifest = createTestManifest()
        val jsonString = json.encodeToString(manifest)
        println("=== Manifest JSON ===")
        println(jsonString)

        val decoded = json.decodeFromString<WasmlineManifest>(jsonString)

        assertEquals(manifest.pluginId, decoded.pluginId)
        assertEquals(manifest.version, decoded.version)
        assertEquals(manifest.versionCode, decoded.versionCode)
        assertEquals(manifest.minSdkVersion, decoded.minSdkVersion)
        assertEquals(manifest.displayName, decoded.displayName)
        assertEquals(manifest.author, decoded.author)
        assertEquals(manifest.description, decoded.description)
        assertEquals(manifest.iconUrl, decoded.iconUrl)
        assertEquals(manifest.homePageUrl, decoded.homePageUrl)
        assertEquals(manifest.buildTimestamp, decoded.buildTimestamp)
        assertEquals(manifest.metadata, decoded.metadata)
        assertEquals(manifest.artifacts.size, decoded.artifacts.size)
        manifest.artifacts.forEachIndexed { i, artifact ->
            assertEquals(artifact.type, decoded.artifacts[i].type)
            assertEquals(artifact.url, decoded.artifacts[i].url)
            assertEquals(artifact.sha256, decoded.artifacts[i].sha256)
            assertEquals(artifact.targetCpu, decoded.artifacts[i].targetCpu)
            assertEquals(artifact.targetOs, decoded.artifacts[i].targetOs)
            assertEquals(artifact.targetCompilerVersion, decoded.artifacts[i].targetCompilerVersion)
            assertEquals(artifact.is64Bit, decoded.artifacts[i].is64Bit)
        }
        println("JSON round-trip serialization successful.")
    }

    @Test
    fun `test JSON envelope round-trip serialization`() {
        printHeader("Test: JSON Envelope Round-Trip")

        val envelope = signManifest(createTestManifest())
        val jsonString = json.encodeToString(envelope)
        println("=== Envelope JSON ===")
        println(jsonString)

        val decoded = json.decodeFromString<SignedManifestEnvelope>(jsonString)

        assertTrue(envelope.signature.contentEquals(decoded.signature))
        assertEquals(envelope.algorithm, decoded.algorithm)
        assertEquals(envelope.publicKeyId, decoded.publicKeyId)
        assertEquals(envelope.manifest.pluginId, decoded.manifest.pluginId)
        println("JSON envelope round-trip serialization successful.")
    }

    @Test
    fun `test Protobuf manifest round-trip serialization`() {
        printHeader("Test: Protobuf Manifest Round-Trip")

        val manifest = createTestManifest()
        val bytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), manifest)
        println("Protobuf manifest size: ${bytes.size} bytes")

        val decoded = ProtoBuf.decodeFromByteArray(WasmlineManifest.serializer(), bytes)

        assertEquals(manifest.pluginId, decoded.pluginId)
        assertEquals(manifest.version, decoded.version)
        assertEquals(manifest.versionCode, decoded.versionCode)
        assertEquals(manifest.artifacts.size, decoded.artifacts.size)
        assertEquals(manifest.metadata, decoded.metadata)
        println("Protobuf manifest round-trip serialization successful.")
    }

    @Test
    fun `test Protobuf envelope round-trip serialization`() {
        printHeader("Test: Protobuf Envelope Round-Trip")

        val envelope = signManifest(createTestManifest())
        val bytes = ProtoBuf.encodeToByteArray(SignedManifestEnvelope.serializer(), envelope)
        println("Protobuf envelope size: ${bytes.size} bytes")

        val decoded = ProtoBuf.decodeFromByteArray(SignedManifestEnvelope.serializer(), bytes)

        assertTrue(envelope.signature.contentEquals(decoded.signature))
        assertEquals(envelope.algorithm, decoded.algorithm)
        assertEquals(envelope.manifest.pluginId, decoded.manifest.pluginId)
        println("Protobuf envelope round-trip serialization successful.")
    }

    // ==================== Signature Tests ====================

    @Test
    fun `test Ed25519 signing and verification success`() {
        printHeader("Test: Ed25519 Valid Signature")

        val manifest = createTestManifest()
        val envelope = signManifest(manifest)

        println("Signature size: ${envelope.signature.size} bytes")
        println("Algorithm: ${envelope.algorithm}")

        val isVerified = verifyEnvelope(envelope)

        println("Signature valid: $isVerified")
        assertTrue(isVerified, "Signature must be valid with matching key pair")
        assertEquals(manifest.pluginId, envelope.manifest.pluginId)
        assertEquals(manifest.versionCode, envelope.manifest.versionCode)
    }

    @Test
    fun `test Ed25519 full envelope encode-decode-verify flow`() {
        printHeader("Test: Full Envelope Encode-Decode-Verify")

        val envelope = signManifest(createTestManifest())

        // Simulate network transmission: encode → decode
        val wireBytes = ProtoBuf.encodeToByteArray(SignedManifestEnvelope.serializer(), envelope)
        val received = ProtoBuf.decodeFromByteArray(SignedManifestEnvelope.serializer(), wireBytes)

        println("Wire size: ${wireBytes.size} bytes")

        val isVerified = verifyEnvelope(received)

        assertTrue(isVerified, "Signature must survive encode-decode round-trip")
        assertEquals(envelope.manifest.pluginId, received.manifest.pluginId)
        println("Full encode-decode-verify flow successful.")
    }

    @Test
    fun `test signature verification fails with wrong public key`() {
        printHeader("Test: Signature Failure (Wrong Key)")

        val envelope = signManifest(createTestManifest())

        // Use a fabricated 32-byte key that doesn't match
        val wrongKey = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".decodeHex()
        val isVerified = verifyEnvelope(envelope, wrongKey)

        println("Verification with wrong key: $isVerified")
        assertFalse(isVerified, "Signature verification must fail with a mismatched public key")
    }

    @Test
    fun `test signature verification fails with tampered manifest`() {
        printHeader("Test: Signature Failure (Tampered Data)")

        val envelope = signManifest(createTestManifest())

        // Tamper with the manifest after signing
        val tampered = envelope.copy(
            manifest = envelope.manifest.copy(pluginId = "com.evil.tampered"),
        )

        val isVerified = verifyEnvelope(tampered)

        println("Verification after tampering: $isVerified")
        assertFalse(isVerified, "Signature verification must fail when manifest data is tampered")
    }

    @Test
    fun `test signature verification fails with corrupted signature`() {
        printHeader("Test: Signature Failure (Corrupted Signature)")

        val envelope = signManifest(createTestManifest())

        // Corrupt the signature bytes
        val corruptedSig = envelope.signature.copyOf()
        corruptedSig[0] = (corruptedSig[0].toInt() xor 0xFF).toByte()
        val corrupted = envelope.copy(signature = corruptedSig)

        val isVerified = verifyEnvelope(corrupted)

        println("Verification with corrupted signature: $isVerified")
        assertFalse(isVerified, "Signature verification must fail with corrupted signature bytes")
    }

    // ==================== Model Default Value Tests ====================

    @Test
    fun `test manifest optional fields default to null`() {
        printHeader("Test: Manifest Optional Defaults")

        val minimal = WasmlineManifest(
            pluginId = "crow.wasmline.minimal",
            version = "0.1.0",
            versionCode = 1,
            minSdkVersion = "0.9.0",
            buildTimestamp = 0L,
            artifacts = listOf(
                WasmlineArtifact(
                    type = WasmlineArtifactType.PWASM,
                    url = "lib.pwasm",
                    sha256 = "abc123",
                ),
            ),
        )

        assertNull(minimal.displayName)
        assertNull(minimal.author)
        assertNull(minimal.description)
        assertNull(minimal.iconUrl)
        assertNull(minimal.homePageUrl)
        assertEquals(emptyMap(), minimal.metadata)
        println("Optional field defaults verified.")
    }

    @Test
    fun `test artifact optional fields default correctly`() {
        printHeader("Test: Artifact Optional Defaults")

        val artifact = WasmlineArtifact(
            type = WasmlineArtifactType.PWASM,
            url = "lib.pwasm",
            sha256 = "abc123",
        )

        assertNull(artifact.targetCpu)
        assertNull(artifact.targetOs)
        assertNull(artifact.targetCompilerVersion)
        assertTrue(artifact.is64Bit, "is64Bit should default to true")
        println("Artifact default values verified.")
    }

    @Test
    fun `test envelope default values`() {
        printHeader("Test: Envelope Default Values")

        val envelope = SignedManifestEnvelope(
            signature = byteArrayOf(0),
            manifest = WasmlineManifest(
                pluginId = "test",
                version = "1.0.0",
                versionCode = 1,
                minSdkVersion = "0.9.0",
                buildTimestamp = 0L,
                artifacts = emptyList(),
            ),
        )

        assertEquals("Ed25519", envelope.algorithm, "Default algorithm should be Ed25519")
        assertNull(envelope.publicKeyId, "publicKeyId should default to null")
        println("Envelope default values verified.")
    }

    // ==================== Artifact Type Tests ====================

    @Test
    fun `test all artifact types serialize correctly`() {
        printHeader("Test: All Artifact Types")

        for (type in WasmlineArtifactType.entries) {
            val artifact = WasmlineArtifact(
                type = type,
                url = "lib.${type.name.lowercase()}",
                sha256 = "hash_${type.name}",
            )

            val bytes = ProtoBuf.encodeToByteArray(WasmlineArtifact.serializer(), artifact)
            val decoded = ProtoBuf.decodeFromByteArray(WasmlineArtifact.serializer(), bytes)

            assertEquals(type, decoded.type)
            assertEquals(artifact.url, decoded.url)
            assertEquals(artifact.sha256, decoded.sha256)
            println("  ${type.name}: OK (${bytes.size} bytes)")
        }
        println("All artifact types serialize correctly.")
    }

    // ==================== Equality Tests ====================

    @Test
    fun `test SignedManifestEnvelope equality and hashCode`() {
        printHeader("Test: Envelope Equality & HashCode")

        val envelope1 = signManifest(createTestManifest())

        // Same content → equal
        val envelope2 = envelope1.copy()
        assertEquals(envelope1, envelope2)
        assertEquals(envelope1.hashCode(), envelope2.hashCode())

        // Different signature → not equal
        val differentSig = envelope1.copy(signature = byteArrayOf(0, 1, 2))
        assertNotEquals(envelope1, differentSig)

        // Different algorithm → not equal
        val differentAlgo = envelope1.copy(algorithm = "ECDSA-P256")
        assertNotEquals(envelope1, differentAlgo)

        // Different publicKeyId → not equal
        val withKeyId = envelope1.copy(publicKeyId = "key-001")
        assertNotEquals(envelope1, withKeyId)

        println("Envelope equality and hashCode verified.")
    }
}
