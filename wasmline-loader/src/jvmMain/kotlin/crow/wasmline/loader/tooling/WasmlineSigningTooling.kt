package crow.wasmline.loader.tooling

import crow.wasmline.loader.internal.crypto.Ed25519
import okio.ByteString.Companion.toByteString
import crow.wasmline.loader.internal.crypto.generateEd25519KeyPair as generateInternalEd25519KeyPair

/**
 * Marks JVM hooks shared with Wasmline build tools but excluded from the runtime API.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This API is reserved for Wasmline build tooling.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
public annotation class WasmlineLoaderToolingApi

/**
 * Contains immutable Ed25519 key material returned to Wasmline JVM build tools.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@WasmlineLoaderToolingApi
public class WasmlineSigningKeyPair internal constructor(publicKey: ByteArray, privateKey: ByteArray) {
    private val publicKeyBytes = publicKey.copyOf()
    private val privateKeyBytes = privateKey.copyOf()

    public val publicKey: ByteArray
        get() = publicKeyBytes.copyOf()

    public val privateKey: ByteArray
        get() = privateKeyBytes.copyOf()
}

/**
 * Exposes the Loader's Ed25519 implementation to Wasmline JVM build tools.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@WasmlineLoaderToolingApi
public object WasmlineSigningTooling {
    /** Generates a new Ed25519 signing key pair. */
    public fun generateEd25519KeyPair(): WasmlineSigningKeyPair = generateInternalEd25519KeyPair().toToolingKeyPair()

    /** Signs one exact message with an Ed25519 private key. */
    public fun signEd25519(message: ByteArray, privateKey: ByteArray): ByteArray =
        Ed25519.sign(message.toByteString(), privateKey.toByteString()).toByteArray()

    private fun crow.wasmline.loader.internal.crypto.KeyPair.toToolingKeyPair(): WasmlineSigningKeyPair = WasmlineSigningKeyPair(
        publicKey = publicKey.toByteArray(),
        privateKey = privateKey.toByteArray(),
    )
}
