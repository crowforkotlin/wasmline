package crow.wasmline.plugin.core.aot

import crow.wasmline.WasmlineEngineKind
import crow.wasmline.loader.model.WasmlineAotCompatibilityProfile
import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Contains the generated AOT compatibility catalog resource.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@Serializable
data class AotCompatibilityLock(
    val schemaVersion: Int,
    val generatedBy: String,
    val sourceManifest: String,
    val currentDefaultProfileIdsByBackend: Map<WasmlineEngineKind, String>,
    val profiles: List<AotCompatibilityProfileSpec>,
    val profileCompilerBindings: List<AotProfileCompilerBinding>,
    val compilerAssets: List<AotCompilerAssetSpec>,
)

/**
 * Defines one immutable backend-specific AOT compatibility profile.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@Serializable
data class AotCompatibilityProfileSpec(
    val id: String,
    val artifactBackend: WasmlineEngineKind,
    val wasmtimeVersion: String,
    val wasmtimeDistributionVersion: String,
    val wasmtimeSourceRevision: String,
    val serializedArtifactFormatIdentity: String,
    val compileProfileSchemaVersion: Int,
    val engineConfigurationProfile: String,
    val introducedInWasmlineVersion: String,
) {
    /** Converts this catalog profile to signed manifest metadata. */
    fun toManifestProfile(): WasmlineAotCompatibilityProfile = WasmlineAotCompatibilityProfile(
        id = id,
        artifactBackend = artifactBackend,
        wasmtimeVersion = wasmtimeVersion,
        wasmtimeDistributionVersion = wasmtimeDistributionVersion,
        compileProfileSchemaVersion = compileProfileSchemaVersion,
    )
}

/**
 * Binds one AOT profile and build host to a deduplicated compiler archive.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@Serializable
data class AotProfileCompilerBinding(
    val profileId: String,
    val artifactBackend: WasmlineEngineKind,
    val buildHost: String,
    val compilerArchiveSha256: String,
)

/**
 * Defines one immutable compiler archive and extracted executable identity.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@Serializable
data class AotCompilerAssetSpec(
    val buildHost: String,
    val distribution: String,
    val archiveFormat: AotCompilerArchiveFormat,
    val assetId: String,
    val archiveName: String,
    val downloadUrls: List<String>,
    val archiveSha256: String,
    val archiveSize: Long,
    val executableRelativePath: String,
    val executableSha256: String,
)

/**
 * Identifies the supported physical compiler archive encodings.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@Serializable
enum class AotCompilerArchiveFormat {
    TAR_GZ,
    ZIP,
}

/**
 * Loads and queries the generated immutable AOT compatibility catalog.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
object AotCompatibilityCatalog {
    const val RESOURCE_PATH: String = "META-INF/wasmline/aot/aot-compatibility-lock.json"

    private val json = Json { ignoreUnknownKeys = false }
    private val lock: AotCompatibilityLock by lazy(::loadAndValidate)

    /** Returns every catalog profile in deterministic order. */
    fun profiles(): List<AotCompatibilityProfileSpec> = lock.profiles

    /** Returns one exact profile or fails with a stable catalog diagnostic. */
    fun requireProfile(id: String): AotCompatibilityProfileSpec = lock.profiles.singleOrNull { it.id == id }
        ?: error("Unknown AOT compatibility profile '$id'.")

    /** Returns the current release profile for one backend. */
    fun currentDefaultProfile(backend: WasmlineEngineKind): AotCompatibilityProfileSpec =
        requireProfile(lock.currentDefaultProfileIdsByBackend[backend] ?: error("No default AOT profile for $backend."))

    /** Resolves one compiler asset for a profile and build host. */
    fun requireCompilerAsset(profileId: String, buildHost: String): AotCompilerAssetSpec {
        val binding = lock.profileCompilerBindings.singleOrNull {
            it.profileId == profileId && it.buildHost == buildHost
        } ?: error("AOT profile '$profileId' does not provide a compiler for build host '$buildHost'.")
        return lock.compilerAssets.singleOrNull { it.archiveSha256 == binding.compilerArchiveSha256 }
            ?: error("AOT compiler archive '${binding.compilerArchiveSha256}' is missing from the catalog.")
    }

    /** Returns the build hosts with a locked compiler asset for one profile. */
    fun buildHosts(profileId: String): List<String> {
        requireProfile(profileId)
        return lock.profileCompilerBindings
            .asSequence()
            .filter { it.profileId == profileId }
            .map(AotProfileCompilerBinding::buildHost)
            .distinct()
            .sorted()
            .toList()
    }

    /** Resolves version and explicit-ID selectors for the requested artifact backends. */
    fun resolveProfiles(
        wasmtimeVersions: Collection<String>,
        profileIds: Collection<String>,
        artifactBackends: Set<WasmlineEngineKind>,
    ): List<AotCompatibilityProfileSpec> {
        require(artifactBackends.isNotEmpty()) { "At least one AOT artifact backend is required." }
        val requestedVersions = wasmtimeVersions.map(String::trim).filter(String::isNotEmpty).distinct()
        requestedVersions.forEach { version ->
            require(SEMANTIC_VERSION_PATTERN.matches(version)) {
                "AOT Wasmtime version must use x.y.z: '$version'."
            }
        }
        val normalizedVersions = requestedVersions.sortedWith { left, right -> compareSemanticVersions(left, right) }
        val normalizedIds = profileIds.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
        if ((wasmtimeVersions.isNotEmpty() || profileIds.isNotEmpty()) && normalizedVersions.isEmpty() && normalizedIds.isEmpty()) {
            error("Explicit AOT compatibility selectors must not be empty.")
        }

        val selected = linkedMapOf<String, AotCompatibilityProfileSpec>()
        if (normalizedVersions.isEmpty() && normalizedIds.isEmpty()) {
            artifactBackends.sortedBy(WasmlineEngineKind::name).forEach { backend ->
                currentDefaultProfile(backend).also { selected[it.id] = it }
            }
        } else {
            normalizedVersions.forEach { version ->
                artifactBackends.forEach { backend ->
                    val matches = lock.profiles.filter {
                        it.wasmtimeVersion == version && it.artifactBackend == backend
                    }
                    when (matches.size) {
                        0 -> error("AOT catalog has no $backend profile for Wasmtime $version.")

                        1 -> selected[matches.single().id] = matches.single()

                        else -> error(
                            "AOT catalog has multiple $backend profiles for Wasmtime $version; " +
                                "select a complete profile ID.",
                        )
                    }
                }
            }
            normalizedIds.forEach { id ->
                val profile = requireProfile(id)
                require(profile.artifactBackend in artifactBackends) {
                    "AOT profile '$id' uses unrequested backend ${profile.artifactBackend}."
                }
                selected[id] = profile
            }
        }
        check(selected.isNotEmpty()) { "AOT compatibility profile selection is empty." }
        return selected.values.sortedWith { left, right ->
            val versionOrder = compareSemanticVersions(left.wasmtimeVersion, right.wasmtimeVersion)
            if (versionOrder != 0) {
                versionOrder
            } else {
                compareValuesBy(left, right, { it.artifactBackend.name }, AotCompatibilityProfileSpec::id)
            }
        }
    }

    /** Calculates the canonical profile ID used by the version synchronizer. */
    fun calculateCompatibilityId(profile: AotCompatibilityProfileSpec): String {
        val fields = sortedMapOf(
            "artifactBackend" to profile.artifactBackend.name,
            "compileProfileSchemaVersion" to profile.compileProfileSchemaVersion.toString(),
            "engineConfigurationProfile" to profile.engineConfigurationProfile,
            "serializedArtifactFormatIdentity" to profile.serializedArtifactFormatIdentity,
            "wasmtimeDistributionVersion" to profile.wasmtimeDistributionVersion,
            "wasmtimeSourceRevision" to profile.wasmtimeSourceRevision,
            "wasmtimeVersion" to profile.wasmtimeVersion,
        )
        val bytes = buildString {
            append("wasmline.aot-compatibility-profile\u0000")
            fields.forEach { (key, value) -> append(key).append('=').append(value).append('\n') }
        }.encodeToByteArray()
        return "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    }

    private fun loadAndValidate(): AotCompatibilityLock {
        val stream = AotCompatibilityCatalog::class.java.classLoader.getResourceAsStream(RESOURCE_PATH)
            ?: error("Missing AOT compatibility catalog resource '$RESOURCE_PATH'.")
        val value = stream.bufferedReader().use { json.decodeFromString<AotCompatibilityLock>(it.readText()) }
        require(value.schemaVersion == 1) { "Unsupported AOT compatibility catalog schema ${value.schemaVersion}." }
        require(value.sourceManifest == "scripts/versions.json") { "Invalid AOT compatibility catalog source." }
        require(value.profiles.map(AotCompatibilityProfileSpec::id).distinct().size == value.profiles.size) {
            "AOT compatibility catalog contains duplicate profile IDs."
        }
        value.profiles.forEach { profile ->
            require(SEMANTIC_VERSION_PATTERN.matches(profile.wasmtimeVersion)) {
                "AOT compatibility profile '${profile.id}' has an invalid Wasmtime version."
            }
            require(calculateCompatibilityId(profile) == profile.id) {
                "AOT compatibility profile '${profile.id}' does not match its canonical descriptor."
            }
        }
        require(value.compilerAssets.map(AotCompilerAssetSpec::archiveSha256).distinct().size == value.compilerAssets.size) {
            "AOT compatibility catalog contains duplicate compiler archive digests."
        }
        value.compilerAssets.forEach { asset ->
            require(asset.distribution == REQUIRED_COMPILER_DISTRIBUTION) {
                "AOT compiler asset '${asset.archiveName}' must use the full Wasmtime distribution."
            }
        }
        value.profileCompilerBindings.forEach { binding ->
            val profile = value.profiles.singleOrNull { it.id == binding.profileId }
                ?: error("AOT compiler binding references unknown profile '${binding.profileId}'.")
            require(profile.artifactBackend == binding.artifactBackend) {
                "AOT compiler binding backend does not match profile '${binding.profileId}'."
            }
            require(value.compilerAssets.any { it.archiveSha256 == binding.compilerArchiveSha256 }) {
                "AOT compiler binding references unknown archive '${binding.compilerArchiveSha256}'."
            }
        }
        return value
    }

    private fun compareSemanticVersions(left: String, right: String): Int {
        val leftParts = left.split('.')
        val rightParts = right.split('.')
        leftParts.indices.forEach { index ->
            val order = compareNumericIdentifier(leftParts[index], rightParts[index])
            if (order != 0) return order
        }
        return 0
    }

    private fun compareNumericIdentifier(left: String, right: String): Int {
        val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
        val normalizedRight = right.trimStart('0').ifEmpty { "0" }
        val lengthOrder = normalizedLeft.length.compareTo(normalizedRight.length)
        return if (lengthOrder != 0) lengthOrder else normalizedLeft.compareTo(normalizedRight)
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private val SEMANTIC_VERSION_PATTERN: Regex = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
    private const val REQUIRED_COMPILER_DISTRIBUTION: String = "FULL"
}
