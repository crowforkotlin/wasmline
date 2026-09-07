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
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@Serializable
data class AotCompatibilityLock(
    val schemaVersion: Int,
    val generatedBy: String,
    val sourceCatalog: String,
    val currentDefaultProfileIdsByBackend: Map<WasmlineEngineKind, String>,
    val releaseCatalog: WasmlineAotReleaseCatalog,
    val profiles: List<AotCompatibilityProfileSpec>,
    val profileCompilerBindings: List<AotProfileCompilerBinding>,
    val compilerAssets: List<AotCompilerAssetSpec>,
)

/**
 * Defines one immutable backend-specific AOT compatibility profile.
 *
 * Date: 2026-08-29
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
 * Date: 2026-08-29
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
 * Date: 2026-08-29
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
 * Date: 2026-08-29
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
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
object AotCompatibilityCatalog {
    const val RESOURCE_PATH: String = "META-INF/wasmline/aot/aot-compatibility-lock.json"

    private val json = Json { ignoreUnknownKeys = false }
    private val lock: AotCompatibilityLock by lazy(::loadAndValidate)
    private val publicCatalog: WasmlineAotReleaseCatalog by lazy(::loadPublicCatalog)

    /** Returns every catalog profile in deterministic order. */
    fun profiles(): List<AotCompatibilityProfileSpec> = lock.profiles

    /** Returns the detailed local release catalog with immutable profile bindings. */
    fun releaseCatalog(): WasmlineAotReleaseCatalog = lock.releaseCatalog

    /** Returns the public catalog packaged with the current Wasmline release. */
    fun publicReleaseCatalog(): WasmlineAotReleaseCatalog = publicCatalog

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

    /** Resolves one Wasmline release selector for the requested artifact backends. */
    fun resolveSelection(
        selection: AotCompatibilitySelection,
        manifestMinimumWasmlineVersion: String,
        artifactBackends: Set<WasmlineEngineKind>,
    ): AotCompatibilityResolution {
        // Force validation of the packaged public view before compiler resolution.
        publicReleaseCatalog()
        return AotCompatibilitySelectionResolver.resolve(
            selection = selection,
            manifestMinimumWasmlineVersion = manifestMinimumWasmlineVersion,
            releaseCatalog = lock.releaseCatalog,
            profiles = lock.profiles,
            requestedBackends = artifactBackends,
        )
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
        require(value.sourceCatalog == "aot-compatibility.json") { "Invalid AOT compatibility catalog source." }
        require(value.profiles.map(AotCompatibilityProfileSpec::id).distinct().size == value.profiles.size) {
            "AOT compatibility catalog contains duplicate profile IDs."
        }
        val profilesById = value.profiles.associateBy(AotCompatibilityProfileSpec::id)
        value.profiles.forEach { profile ->
            require(PROFILE_ID_PATTERN.matches(profile.id)) {
                "AOT compatibility profile '${profile.id}' has an invalid ID."
            }
            require(SEMANTIC_VERSION_PATTERN.matches(profile.wasmtimeVersion)) {
                "AOT compatibility profile '${profile.id}' has an invalid Wasmtime version."
            }
            require(WASMTIME_DISTRIBUTION_VERSION.matches(profile.wasmtimeDistributionVersion)) {
                "AOT compatibility profile '${profile.id}' has an invalid Wasmtime distribution version."
            }
            require(profile.wasmtimeDistributionVersion.substringBeforeLast('.') == profile.wasmtimeVersion) {
                "AOT compatibility profile '${profile.id}' has inconsistent Wasmtime versions."
            }
            require(SOURCE_REVISION_PATTERN.matches(profile.wasmtimeSourceRevision)) {
                "AOT compatibility profile '${profile.id}' has an invalid source revision."
            }
            require(profile.serializedArtifactFormatIdentity.isNotBlank()) {
                "AOT compatibility profile '${profile.id}' has an empty artifact format identity."
            }
            require(profile.compileProfileSchemaVersion > 0) {
                "AOT compatibility profile '${profile.id}' has an invalid compile profile schema version."
            }
            require(profile.engineConfigurationProfile.isNotBlank()) {
                "AOT compatibility profile '${profile.id}' has an empty engine configuration profile."
            }
            require(SEMANTIC_VERSION_PATTERN.matches(profile.introducedInWasmlineVersion)) {
                "AOT compatibility profile '${profile.id}' has an invalid introduced Wasmline version."
            }
            require(compareWasmlineVersions(profile.introducedInWasmlineVersion, value.releaseCatalog.currentWasmlineVersion) <= 0) {
                "AOT compatibility profile '${profile.id}' is introduced after the current Wasmline release."
            }
            require(calculateCompatibilityId(profile) == profile.id) {
                "AOT compatibility profile '${profile.id}' does not match its canonical descriptor."
            }
        }
        require(value.compilerAssets.map(AotCompilerAssetSpec::archiveSha256).distinct().size == value.compilerAssets.size) {
            "AOT compatibility catalog contains duplicate compiler archive digests."
        }
        val assetsByDigest = value.compilerAssets.associateBy(AotCompilerAssetSpec::archiveSha256)
        val assetsByDistribution = value.compilerAssets
            .groupBy(::validateCompilerAsset)
            .mapValues { (_, assets) -> assets.map(AotCompilerAssetSpec::buildHost).toSet() }
        value.compilerAssets.forEach { asset ->
            require(asset.distribution == REQUIRED_COMPILER_DISTRIBUTION) {
                "AOT compiler asset '${asset.archiveName}' must use the full Wasmtime distribution."
            }
        }
        require(
            value.compilerAssets.map(AotCompilerAssetSpec::archiveSha256) ==
                value.compilerAssets.map(AotCompilerAssetSpec::archiveSha256).sorted(),
        ) {
            "AOT compiler assets must be sorted by archive digest."
        }
        require(value.profileCompilerBindings.isNotEmpty()) {
            "AOT compatibility catalog must contain compiler bindings."
        }
        val bindingKeys = mutableSetOf<Pair<String, String>>()
        val hostsByProfile = value.profiles.associate { it.id to mutableSetOf<String>() }
        val referencedAssets = mutableSetOf<String>()
        value.profileCompilerBindings.forEach { binding ->
            val profile = profilesById[binding.profileId]
                ?: error("AOT compiler binding references unknown profile '${binding.profileId}'.")
            require(profile.artifactBackend == binding.artifactBackend) {
                "AOT compiler binding backend does not match profile '${binding.profileId}'."
            }
            val asset = assetsByDigest[binding.compilerArchiveSha256]
                ?: error("AOT compiler binding references unknown archive '${binding.compilerArchiveSha256}'.")
            val assetIdentity = ASSET_ID_PATTERN.matchEntire(asset.assetId)
                ?: error("AOT compiler asset '${asset.archiveName}' has an invalid asset identity.")
            require(assetIdentity.groups["host"]?.value == binding.buildHost) {
                "AOT compiler binding host does not match archive '${asset.archiveName}'."
            }
            require(assetIdentity.groups["distribution"]?.value == profile.wasmtimeDistributionVersion) {
                "AOT compiler binding distribution does not match profile '${binding.profileId}'."
            }
            require(bindingKeys.add(binding.profileId to binding.buildHost)) {
                "AOT compiler bindings contain a duplicate profile/build-host pair."
            }
            hostsByProfile.getValue(binding.profileId).add(binding.buildHost)
            referencedAssets.add(binding.compilerArchiveSha256)
        }
        value.profiles.forEach { profile ->
            val expectedHosts = assetsByDistribution[profile.wasmtimeDistributionVersion]
                ?: error("AOT profile '${profile.id}' has no compiler assets.")
            require(hostsByProfile.getValue(profile.id) == expectedHosts) {
                "AOT profile '${profile.id}' must provide exactly one compiler binding for every build host."
            }
        }
        require(referencedAssets == assetsByDigest.keys) {
            "Every AOT compiler asset must be referenced by a profile binding."
        }
        require(
            value.profileCompilerBindings.map { it.profileId to it.buildHost } ==
                value.profileCompilerBindings.map { it.profileId to it.buildHost }
                    .sortedWith(compareBy({ it.first }, { it.second })),
        ) {
            "AOT compiler bindings must be sorted by profile ID and build host."
        }
        value.releaseCatalog.validate(requireProfileBindings = true, profiles = value.profiles)
        val referencedProfiles = value.releaseCatalog.ranges
            .flatMap { it.profileIdsByBackend.values }
            .toSet()
        require(referencedProfiles == profilesById.keys) {
            "Every AOT profile must be bound to a public release generation."
        }
        value.releaseCatalog.ranges.forEach { range ->
            range.profileIdsByBackend.values.forEach { profileId ->
                val profile = profilesById.getValue(profileId)
                require(compareWasmlineVersions(profile.introducedInWasmlineVersion, range.fromWasmlineVersion) <= 0) {
                    "AOT generation ${range.aotGeneration} uses a profile introduced after its range start."
                }
            }
        }
        require(value.releaseCatalog.ranges.last().profileIdsByBackend == value.currentDefaultProfileIdsByBackend) {
            "Current AOT defaults do not match the final release generation."
        }
        return value
    }

    private fun validateCompilerAsset(asset: AotCompilerAssetSpec): String {
        val identity = ASSET_ID_PATTERN.matchEntire(asset.assetId)
            ?: error("AOT compiler asset '${asset.archiveName}' has an invalid assetId.")
        val distribution = identity.groups["distribution"]!!.value
        val host = identity.groups["host"]!!.value
        require(host == asset.buildHost) {
            "AOT compiler asset '${asset.archiveName}' assetId host does not match buildHost."
        }
        require(asset.archiveSha256.matches(DIGEST_PATTERN)) {
            "AOT compiler asset '${asset.archiveName}' has an invalid archive digest."
        }
        require(asset.executableSha256.matches(DIGEST_PATTERN)) {
            "AOT compiler asset '${asset.archiveName}' has an invalid executable digest."
        }
        require(asset.archiveSize > 0) {
            "AOT compiler asset '${asset.archiveName}' has an invalid archive size."
        }
        val extension = when (asset.archiveFormat) {
            AotCompilerArchiveFormat.TAR_GZ -> ".tar.gz"
            AotCompilerArchiveFormat.ZIP -> ".zip"
        }
        require(asset.archiveName == asset.assetId + extension) {
            "AOT compiler asset '${asset.archiveName}' does not match its assetId and archive format."
        }
        require(asset.downloadUrls.isNotEmpty() && asset.downloadUrls.distinct().size == asset.downloadUrls.size) {
            "AOT compiler asset '${asset.archiveName}' has invalid download URLs."
        }
        require(asset.downloadUrls.all { url -> url.startsWith("https://") && url.none(Char::isWhitespace) }) {
            "AOT compiler asset '${asset.archiveName}' download URLs must use HTTPS."
        }
        val executablePath = asset.executableRelativePath.replace('\\', '/')
        val pathParts = executablePath.split('/')
        require(!executablePath.startsWith('/') && pathParts.none { part -> part.isEmpty() || part == "." || part == ".." }) {
            "AOT compiler asset '${asset.archiveName}' has an unsafe executable path."
        }
        require(pathParts.firstOrNull() == asset.assetId) {
            "AOT compiler asset '${asset.archiveName}' executable path must stay inside its archive directory."
        }
        val executableName = if (host.endsWith("windows")) "wasmtime.exe" else "wasmtime"
        require(pathParts.lastOrNull() == executableName) {
            "AOT compiler asset '${asset.archiveName}' executable path must end in $executableName."
        }
        return distribution
    }

    private fun loadPublicCatalog(): WasmlineAotReleaseCatalog {
        val stream = AotCompatibilityCatalog::class.java.classLoader
            .getResourceAsStream(WasmlineAotReleaseCatalog.RESOURCE_PATH)
            ?: error("Missing public AOT compatibility resource '${WasmlineAotReleaseCatalog.RESOURCE_PATH}'.")
        val value = stream.bufferedReader().use { WasmlineAotReleaseCatalogCodec.decodePublic(it.readText()) }
        require(value == lock.releaseCatalog.withoutProfileBindings()) {
            "The public AOT compatibility resource does not match the detailed local catalog."
        }
        return value
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private val SEMANTIC_VERSION_PATTERN: Regex = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
    private val WASMTIME_DISTRIBUTION_VERSION: Regex = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+\\.[1-9][0-9]*$")
    private val SOURCE_REVISION_PATTERN: Regex = Regex("^[0-9a-f]{40}$")
    private val PROFILE_ID_PATTERN: Regex = Regex("^sha256:[0-9a-f]{64}$")
    private val DIGEST_PATTERN: Regex = Regex("^[0-9a-f]{64}$")
    private val ASSET_ID_PATTERN: Regex = Regex(
        "^wasmtime-v(?<distribution>[0-9]+\\.[0-9]+\\.[0-9]+\\.[1-9][0-9]*)-(?<host>[A-Za-z0-9][A-Za-z0-9._-]*)$",
    )
    private const val REQUIRED_COMPILER_DISTRIBUTION: String = "FULL"
}
