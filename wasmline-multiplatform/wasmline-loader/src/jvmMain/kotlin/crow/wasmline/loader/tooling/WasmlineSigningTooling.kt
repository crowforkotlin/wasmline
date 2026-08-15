package crow.wasmline.loader.tooling

import crow.wasmline.loader.internal.crypto.Ed25519
import crow.wasmline.loader.internal.crypto.SignatureAlgorithmId
import okio.ByteString.Companion.toByteString
import crow.wasmline.loader.internal.crypto.generateKeyPair as generateInternalKeyPair

/** Marks JVM tooling hooks that are shared with Wasmline's build tools but are not public runtime API. */
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

/** Immutable key material returned to Wasmline's JVM build tools. */
@WasmlineLoaderToolingApi
public class WasmlineSigningKeyPair internal constructor(publicKey: ByteArray, privateKey: ByteArray) {
    private val publicKeyBytes = publicKey.copyOf()
    private val privateKeyBytes = privateKey.copyOf()

    public val publicKey: ByteArray
        get() = publicKeyBytes.copyOf()

    public val privateKey: ByteArray
        get() = privateKeyBytes.copyOf()
}

/** Narrow JVM facade over the loader's private signing implementation. */
@WasmlineLoaderToolingApi
public object WasmlineSigningTooling {
    public fun generateEd25519KeyPair(): WasmlineSigningKeyPair = generateInternalKeyPair(
        SignatureAlgorithmId.Ed25519,
    ).toToolingKeyPair()

    public fun generateEcdsaP256KeyPair(): WasmlineSigningKeyPair =
        generateInternalKeyPair(SignatureAlgorithmId.EcdsaP256).toToolingKeyPair()

    public fun signEd25519(message: ByteArray, privateKey: ByteArray): ByteArray =
        Ed25519.sign(message.toByteString(), privateKey.toByteString()).toByteArray()

    private fun crow.wasmline.loader.internal.crypto.KeyPair.toToolingKeyPair(): WasmlineSigningKeyPair = WasmlineSigningKeyPair(
        publicKey = publicKey.toByteArray(),
        privateKey = privateKey.toByteArray(),
    )
}
