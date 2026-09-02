package crow.wasmline.test.fixtures

import crow.wasmline.WasmlineArtifactFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Records every generated AOT artifact available to native runtime tests.
 *
 * Date: 2026-09-01
 * Author: crowforkotlin
 */
@Serializable
data class NativeFixtureIndex(val schemaVersion: Int = SCHEMA_VERSION, val fixtures: List<NativeFixtureArtifact>) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported native fixture index schema $schemaVersion." }
        require(fixtures.isNotEmpty()) { "Native fixture index must contain at least one artifact." }
        require(fixtures.map(NativeFixtureArtifact::identity).distinct().size == fixtures.size) {
            "Native fixture index contains duplicate fixture artifacts."
        }
    }

    /** Defines the supported fixture index schema version. */
    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

/**
 * Records one physical AOT artifact and the immutable profile that produced it.
 *
 * Date: 2026-09-01
 * Author: crowforkotlin
 */
@Serializable
data class NativeFixtureArtifact(
    val fixtureId: String,
    val executionModel: String,
    val invocationProtocol: String,
    val artifactFormat: String,
    val artifactBackend: String,
    val requestedTarget: String,
    val normalizedTarget: String,
    val aotCompatibilityProfileId: String,
    val sha256: String,
    val sizeBytes: Long,
    val relativePath: String,
) {
    init {
        require(fixtureId.matches(Regex("[a-z0-9][a-z0-9-]*"))) { "Native fixture artifact has an invalid fixture ID '$fixtureId'." }
        require(executionModel.isNotBlank()) { "Native fixture '$fixtureId' has no execution model." }
        require(invocationProtocol.isNotBlank()) { "Native fixture '$fixtureId' has no invocation protocol." }
        require(artifactBackend.isNotBlank()) { "Native fixture '$fixtureId' has no artifact backend." }
        require(requestedTarget.isNotBlank() && normalizedTarget.isNotBlank()) { "Native fixture '$fixtureId' has an invalid target." }
        require(aotCompatibilityProfileId.startsWith("sha256:") && aotCompatibilityProfileId.length == 71) {
            "Native fixture '$fixtureId' has an invalid AOT compatibility profile ID."
        }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Native fixture '$fixtureId' has an invalid SHA-256 digest." }
        require(sizeBytes > 0) { "Native fixture '$fixtureId' has an invalid artifact size." }
        require(relativePath.isSafeRelativePath()) { "Native fixture '$fixtureId' has an unsafe artifact path '$relativePath'." }
        val expectedSuffix = when (WasmlineArtifactFormat.valueOf(artifactFormat)) {
            WasmlineArtifactFormat.CWASM -> ".cwasm"
            WasmlineArtifactFormat.PWASM -> ".pwasm"
            WasmlineArtifactFormat.RAW_WASM -> error("Native fixture '$fixtureId' must not reference raw Wasm.")
        }
        require(relativePath.endsWith(expectedSuffix)) {
            "Native fixture '$fixtureId' path does not match $artifactFormat: '$relativePath'."
        }
    }

    /** Returns the index identity for one fixture artifact. */
    fun identity(): String = listOf(fixtureId, artifactFormat, requestedTarget, aotCompatibilityProfileId).joinToString("\u0000")
}

/**
 * Reads, writes, and verifies native fixture indexes without trusting file paths or digests.
 *
 * Date: 2026-09-01
 * Author: crowforkotlin
 */
object NativeFixtureIndexes {
    const val FILE_NAME: String = "fixture-index.json"

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /** Writes a deterministic fixture index. */
    fun write(index: NativeFixtureIndex, outputFile: File): File {
        outputFile.parentFile?.let { parent -> check(parent.isDirectory || parent.mkdirs()) }
        outputFile.writeText(json.encodeToString(index))
        return outputFile
    }

    /** Reads one fixture index and validates every indexed artifact. */
    fun readAndValidate(indexFile: File): NativeFixtureIndex {
        require(indexFile.isFile) { "Native fixture index does not exist: ${indexFile.absolutePath}" }
        val index = json.decodeFromString<NativeFixtureIndex>(indexFile.readText())
        validateArtifacts(index, requireNotNull(indexFile.parentFile))
        return index
    }

    /** Validates every indexed file against its recorded path, size, and SHA-256 digest. */
    fun validateArtifacts(index: NativeFixtureIndex, rootDirectory: File) {
        val root = rootDirectory.toPath().toAbsolutePath().normalize()
        index.fixtures.forEach { artifact ->
            val resolved = root.resolve(artifact.relativePath).normalize()
            require(resolved.startsWith(root)) { "Native fixture path escapes the index root: ${artifact.relativePath}" }
            val file = resolved.toFile()
            require(file.isFile) { "Native fixture artifact does not exist: ${file.absolutePath}" }
            require(file.length() == artifact.sizeBytes) {
                "Native fixture artifact size mismatch for ${file.absolutePath}: expected ${artifact.sizeBytes}, actual ${file.length()}."
            }
            val actualDigest = sha256Hex(file)
            require(actualDigest == artifact.sha256) {
                "Native fixture artifact SHA-256 mismatch for ${file.absolutePath}: expected ${artifact.sha256}, actual $actualDigest."
            }
        }
    }
}

/**
 * Provides test-side access to verified generated fixture artifacts.
 *
 * Date: 2026-09-01
 * Author: crowforkotlin
 */
class NativeFixtureCatalog private constructor(private val rootDirectory: File, private val index: NativeFixtureIndex) {
    /** Copies one verified fixture artifact into a private temporary file. */
    fun copyArtifact(fixtureId: String, format: WasmlineArtifactFormat, requestedTarget: String, aotCompatibilityProfileId: String): File {
        val artifact = index.fixtures.singleOrNull { item ->
            item.fixtureId == fixtureId &&
                item.artifactFormat == format.name &&
                item.requestedTarget == requestedTarget &&
                item.aotCompatibilityProfileId == aotCompatibilityProfileId
        } ?: error(
            "Native fixture '$fixtureId' has no ${format.name} artifact for target '$requestedTarget' and profile " +
                "'$aotCompatibilityProfileId' in ${rootDirectory.absolutePath}.",
        )
        val source = resolve(artifact)
        val suffix = when (format) {
            WasmlineArtifactFormat.CWASM -> ".cwasm"
            WasmlineArtifactFormat.PWASM -> ".pwasm"
            WasmlineArtifactFormat.RAW_WASM -> error("Native fixture artifacts cannot use raw Wasm.")
        }
        return File.createTempFile("wasmline-$fixtureId-", suffix).apply {
            source.copyTo(this, overwrite = true)
            deleteOnExit()
        }
    }

    /** Returns a verified fixture artifact without copying it. */
    fun requireArtifact(
        fixtureId: String,
        format: WasmlineArtifactFormat,
        requestedTarget: String,
        aotCompatibilityProfileId: String,
    ): File {
        val artifact = index.fixtures.singleOrNull { item ->
            item.fixtureId == fixtureId &&
                item.artifactFormat == format.name &&
                item.requestedTarget == requestedTarget &&
                item.aotCompatibilityProfileId == aotCompatibilityProfileId
        } ?: error(
            "Native fixture '$fixtureId' has no ${format.name} artifact for target '$requestedTarget' and profile " +
                "'$aotCompatibilityProfileId' in ${rootDirectory.absolutePath}.",
        )
        return resolve(artifact)
    }

    /** Resolves the fixture index path supplied by the native AOT Gradle task. */
    companion object {
        const val SYSTEM_PROPERTY: String = "wasmline.native.fixtures.index"

        fun fromSystemProperty(): NativeFixtureCatalog {
            val path = System.getProperty(SYSTEM_PROPERTY)?.takeIf(String::isNotBlank)
                ?: error("Native AOT fixture index is missing. Run :wasmline:nativeAotJvmTest instead of a direct test filter.")
            val indexFile = File(path)
            val index = NativeFixtureIndexes.readAndValidate(indexFile)
            return NativeFixtureCatalog(requireNotNull(indexFile.parentFile), index)
        }
    }

    private fun resolve(artifact: NativeFixtureArtifact): File {
        val root = rootDirectory.toPath().toAbsolutePath().normalize()
        val resolved = root.resolve(artifact.relativePath).normalize()
        require(resolved.startsWith(root)) { "Native fixture path escapes the index root: ${artifact.relativePath}" }
        val file = resolved.toFile()
        require(file.isFile && file.length() == artifact.sizeBytes && sha256Hex(file) == artifact.sha256) {
            "Native fixture artifact changed after index validation: ${file.absolutePath}"
        }
        return file
    }
}

private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

/** Verifies that a fixture path stays within its index root. */
private fun String.isSafeRelativePath(): Boolean {
    val normalized = replace('\\', '/')
    return normalized.isNotBlank() &&
        !normalized.startsWith('/') &&
        !Regex("^[A-Za-z]:/").containsMatchIn(normalized) &&
        normalized.split('/').none { segment -> segment.isEmpty() || segment == "." || segment == ".." }
}
