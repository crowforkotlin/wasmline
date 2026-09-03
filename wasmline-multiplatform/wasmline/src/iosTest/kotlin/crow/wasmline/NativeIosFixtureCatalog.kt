@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package crow.wasmline

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.posix.getenv

/**
 * Reads generated native fixture records for iOS simulator tests.
 *
 * Date: 2026-09-01
 * Author: crowforkotlin
 */
internal object NativeIosFixtureCatalog {
    /** Returns the verified Pulley fixture selected for one runtime contract and profile. */
    fun requirePwasmPath(
        fixtureId: String,
        profileId: String,
        executionModel: WasmlineExecutionModel,
        invocationProtocol: WasmlineInvocationProtocol,
    ): String {
        val indexPath = getenv(INDEX_ENVIRONMENT_VARIABLE)?.toKString()
            ?: error("$INDEX_ENVIRONMENT_VARIABLE is required for iOS native AOT tests.")
        require(indexPath.startsWith('/')) { "Native fixture index must use an absolute path: $indexPath" }
        val rootDirectory = indexPath.substringBeforeLast('/', missingDelimiterValue = "")
        val index = readIndex(indexPath)
        require(index.schemaVersion == INDEX_SCHEMA_VERSION) {
            "Unsupported native fixture index schema ${index.schemaVersion}; expected $INDEX_SCHEMA_VERSION."
        }
        val artifact = index.fixtures.singleOrNull { item ->
            item.fixtureId == fixtureId &&
                item.executionModel == executionModel.name &&
                item.invocationProtocol == invocationProtocol.name &&
                item.artifactFormat == PWASM_FORMAT &&
                item.artifactBackend == PULLEY_BACKEND &&
                item.requestedTarget == PULLEY64_TARGET &&
                item.aotCompatibilityProfileId == profileId
        } ?: error(
            "Native fixture '$fixtureId' has no $PULLEY64_TARGET $PWASM_FORMAT $PULLEY_BACKEND artifact for " +
                "${executionModel.name}/${invocationProtocol.name} and profile '$profileId'. " +
                "Run :wasmline:iosSimulatorArm64Test with -Pwasmline.native.fixtures.targets=$PULLEY64_TARGET.",
        )
        require(artifact.relativePath.isSafeRelativeFixturePath()) {
            "Native fixture '$fixtureId' has an unsafe path '${artifact.relativePath}'."
        }
        require(artifact.relativePath.endsWith(PWASM_SUFFIX, ignoreCase = true)) {
            "Native fixture '$fixtureId' path does not end in $PWASM_SUFFIX: ${artifact.relativePath}"
        }
        require(artifact.sha256.matches(SHA256_PATTERN)) {
            "Native fixture '$fixtureId' has an invalid SHA-256 digest."
        }
        require(artifact.sizeBytes > 0) { "Native fixture '$fixtureId' has an invalid size ${artifact.sizeBytes}." }
        val artifactPath = "$rootDirectory/${artifact.relativePath}"
        require(NSFileManager.defaultManager.fileExistsAtPath(artifactPath)) {
            "Native fixture artifact does not exist: $artifactPath"
        }
        val actualSize = requireNotNull(
            NSFileManager.defaultManager.attributesOfItemAtPath(artifactPath, null)
                ?.get(requireNotNull(NSFileSize)) as? NSNumber,
        ) { "Native fixture artifact has no file size: $artifactPath" }.longLongValue
        require(actualSize == artifact.sizeBytes) {
            "Native fixture artifact size mismatch for $artifactPath: expected ${artifact.sizeBytes}, actual $actualSize."
        }
        require(sha256Hex(artifactPath) == artifact.sha256) {
            "Native fixture artifact SHA-256 mismatch for $artifactPath."
        }
        return artifactPath
    }

    /** Reads and decodes the fixture index emitted by the Gradle fixture task. */
    private fun readIndex(indexPath: String): IosNativeFixtureIndex {
        val text = requireNotNull(
            NSString.create(
                contentsOfFile = indexPath,
                encoding = NSUTF8StringEncoding,
                error = null,
            ),
        ) {
            "Unable to read native fixture index: $indexPath"
        }.toString()
        return JSON.decodeFromString(text)
    }

    /** Computes the SHA-256 digest of an iOS fixture artifact. */
    private fun sha256Hex(path: String): String {
        val data = requireNotNull(NSData.create<NSData>(contentsOfFile = path)) {
            "Unable to read native fixture artifact: $path"
        }
        require(data.length <= UInt.MAX_VALUE.toULong()) {
            "Native fixture artifact is too large for CommonCrypto: $path"
        }
        val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
        digest.usePinned { pinnedDigest ->
            CC_SHA256(
                data = data.bytes,
                len = data.length.toUInt(),
                md = pinnedDigest.addressOf(0),
            )
        }
        return digest.joinToString(separator = "") { byte ->
            byte.toString(16).padStart(2, '0')
        }
    }

    private const val INDEX_ENVIRONMENT_VARIABLE: String = "WASMLINE_NATIVE_FIXTURE_INDEX"
    private const val INDEX_SCHEMA_VERSION: Int = 1
    private const val PULLEY64_TARGET: String = "pulley64"
    private const val PWASM_FORMAT: String = "PWASM"
    private const val PULLEY_BACKEND: String = "PULLEY"
    private const val PWASM_SUFFIX: String = ".pwasm"

    private val JSON = Json { ignoreUnknownKeys = true }
    private val SHA256_PATTERN: Regex = Regex("[0-9a-f]{64}")
}

/**
 * Defines the index fields needed by iOS native AOT fixture selection.
 *
 * Date: 2026-09-01
 * Author: crowforkotlin
 */
@Serializable
private data class IosNativeFixtureIndex(val schemaVersion: Int, val fixtures: List<IosNativeFixtureArtifact>)

/**
 * Defines one generated artifact visible to iOS native AOT tests.
 *
 * Date: 2026-09-01
 * Author: crowforkotlin
 */
@Serializable
private data class IosNativeFixtureArtifact(
    val fixtureId: String,
    val executionModel: String,
    val invocationProtocol: String,
    val artifactFormat: String,
    val artifactBackend: String,
    val requestedTarget: String,
    val aotCompatibilityProfileId: String,
    val sha256: String,
    val sizeBytes: Long,
    val relativePath: String,
)

/** Returns whether a fixture path stays below the fixture index directory. */
private fun String.isSafeRelativeFixturePath(): Boolean {
    val normalized = replace('\\', '/')
    return normalized.isNotBlank() &&
        !normalized.startsWith('/') &&
        !Regex("^[A-Za-z]:/").containsMatchIn(normalized) &&
        normalized.split('/').none { segment -> segment.isEmpty() || segment == "." || segment == ".." }
}
