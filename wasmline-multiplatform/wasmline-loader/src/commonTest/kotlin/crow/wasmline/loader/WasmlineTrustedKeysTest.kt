package crow.wasmline.loader

import crow.wasmline.WasmlineTrustedKeySet
import crow.wasmline.WasmlineTrustedKeys
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/** Verifies exact, wildcard, and algorithm-specific trusted key lookup. */
class WasmlineTrustedKeysTest {

    @Test
    fun `exact match returns correct key`() {
        val key1 = byteArrayOf(1, 2, 3)
        val key2 = byteArrayOf(4, 5, 6)

        val keys = WasmlineTrustedKeySet.Builder()
            .add("Ed25519", keyId = "release", publicKey = key1)
            .add("Ed25519", keyId = "debug", publicKey = key2)
            .build()

        assertContentEquals(key1, keys.getPublicKey("Ed25519", "release"))
        assertContentEquals(key2, keys.getPublicKey("Ed25519", "debug"))
    }

    @Test
    fun `wildcard keyId matches any keyId for algorithm`() {
        val wildcardKey = byteArrayOf(7, 8, 9)

        val keys = WasmlineTrustedKeySet.Builder()
            .add("Ed25519", keyId = null, publicKey = wildcardKey)
            .build()

        assertContentEquals(wildcardKey, keys.getPublicKey("Ed25519", "any-key-id"))
        assertContentEquals(wildcardKey, keys.getPublicKey("Ed25519", null))
    }

    @Test
    fun `exact match takes priority over wildcard`() {
        val exactKey = byteArrayOf(1, 2, 3)
        val wildcardKey = byteArrayOf(7, 8, 9)

        val keys = WasmlineTrustedKeySet.Builder()
            .add("Ed25519", keyId = null, publicKey = wildcardKey)
            .add("Ed25519", keyId = "release", publicKey = exactKey)
            .build()

        assertContentEquals(exactKey, keys.getPublicKey("Ed25519", "release"))
        assertContentEquals(wildcardKey, keys.getPublicKey("Ed25519", "other"))
    }

    @Test
    fun `unknown algorithm returns null`() {
        val keys = WasmlineTrustedKeySet.Builder()
            .add("Ed25519", keyId = null, publicKey = byteArrayOf(1, 2, 3))
            .build()

        assertNull(keys.getPublicKey("EcdsaP256", null))
    }

    @Test
    fun `unknown keyId without wildcard returns null`() {
        val keys = WasmlineTrustedKeySet.Builder()
            .add("Ed25519", keyId = "release", publicKey = byteArrayOf(1, 2, 3))
            .build()

        assertNull(keys.getPublicKey("Ed25519", "unknown"))
    }

    @Test
    fun `addHex decodes hex string correctly`() {
        val hexKey = "0102030405"
        val expectedBytes = byteArrayOf(1, 2, 3, 4, 5)

        val keys = WasmlineTrustedKeySet.Builder()
            .addHex("Ed25519", keyId = null, publicKeyHex = hexKey)
            .build()

        assertContentEquals(expectedBytes, keys.getPublicKey("Ed25519", null))
    }

    @Test
    fun `empty key set returns null for all lookups`() {
        val keys = WasmlineTrustedKeySet.Builder().build()

        assertNull(keys.getPublicKey("Ed25519", null))
        assertNull(keys.getPublicKey("Ed25519", "any"))
    }

    @Test
    fun `fun interface SAM conversion works`() {
        val customKey = byteArrayOf(10, 20, 30)
        val keys = WasmlineTrustedKeys { algorithm, keyId ->
            if (algorithm == "Ed25519" && keyId == "test") customKey else null
        }

        assertContentEquals(customKey, keys.getPublicKey("Ed25519", "test"))
        assertNull(keys.getPublicKey("Ed25519", "other"))
    }
}
