@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline.gradle.extensions

import crow.wasmline.gradle.trust.WasmlineTrustedPublicKeySpecCodec
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import java.io.File
import javax.inject.Inject

/**
 * Configures public keys trusted by this Host application when it loads signed
 * Wasmline packages.
 *
 * The configured keys are generated as a `WasmlineTrustedKeys` implementation
 * in the selected Kotlin source set. This extension never accepts a private key.
 *
 * ```kotlin
 * wasmline {
 *     trust {
 *         publicKey(
 *             algorithm = "Ed25519",
 *             keyId = null,
 *             publicKeyHex = "5a778289...",
 *         )
 *         publicKeyFile(file("keys/package-public.key"))
 *     }
 * }
 * ```
 */
public open class TrustExtension @Inject constructor(project: Project) {
    private val inlinePublicKeys = mutableListOf<InlinePublicKey>()
    private val filePublicKeys = mutableListOf<FilePublicKey>()
    private val objects = project.objects

    /** Kotlin package of the generated `WasmlineTrustedKeys` implementation. */
    public val kotlinPackage: Property<String> = objects.property(String::class.java)
        .convention("crow.wasmline.generated")

    /** Generated Kotlin object name. */
    public val objectName: Property<String> = objects.property(String::class.java)
        .convention("WasmlineHostTrust")

    /**
     * Kotlin source set receiving the generated source in a Multiplatform project.
     * When unset, `commonMain` is used for Kotlin Multiplatform and `main` for Kotlin JVM.
     */
    public val sourceSet: Property<String> = objects.property(String::class.java)

    /** Generated Kotlin source directory. */
    public val generatedSourcesDirectory: DirectoryProperty = objects.directoryProperty()
        .convention(project.layout.buildDirectory.dir("generated/wasmline/trust"))

    /** Adds one trusted public key supplied directly as hexadecimal text. */
    public fun publicKey(algorithm: String = ED25519, keyId: String? = null, publicKeyHex: String) {
        inlinePublicKeys += InlinePublicKey(
            algorithm = requireAlgorithm(algorithm),
            keyId = requireKeyId(keyId),
            publicKeyHex = publicKeyHex.trim().also { normalized ->
                require(normalized.isNotBlank()) { "publicKeyHex must not be blank." }
            },
        )
    }

    /** Adds one trusted public key read from a UTF-8 text file containing hexadecimal text. */
    public fun publicKeyFile(file: File, algorithm: String = ED25519, keyId: String? = null) {
        filePublicKeys += FilePublicKey(
            algorithm = requireAlgorithm(algorithm),
            keyId = requireKeyId(keyId),
            file = file,
        )
    }

    internal fun hasPublicKeys(): Boolean = inlinePublicKeys.isNotEmpty() || filePublicKeys.isNotEmpty()

    internal fun inlinePublicKeySpecs(): List<String> = inlinePublicKeys.map { key ->
        WasmlineTrustedPublicKeySpecCodec.inline(
            algorithm = key.algorithm,
            keyId = key.keyId,
            publicKeyHex = key.publicKeyHex,
        )
    }

    internal fun filePublicKeySpecs(): List<String> = filePublicKeys.map { key ->
        WasmlineTrustedPublicKeySpecCodec.file(
            algorithm = key.algorithm,
            keyId = key.keyId,
            file = key.file,
        )
    }

    internal fun publicKeyFiles(): List<File> = filePublicKeys.map(FilePublicKey::file)

    private fun requireAlgorithm(value: String): String = value.trim().also { normalized ->
        require(normalized.isNotBlank()) { "Public-key algorithm must not be blank." }
    }

    private fun requireKeyId(value: String?): String? = value?.let { keyId ->
        require(keyId.isNotBlank()) { "Public-key keyId must not be blank when specified." }
        keyId
    }

    private data class InlinePublicKey(val algorithm: String, val keyId: String?, val publicKeyHex: String)

    private data class FilePublicKey(val algorithm: String, val keyId: String?, val file: File)

    private companion object {
        const val ED25519: String = "Ed25519"
    }
}
