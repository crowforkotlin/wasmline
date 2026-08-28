package crow.wasmline.loader.model

import crow.wasmline.CoreWasmFeature
import crow.wasmline.RawAbiMetadata
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol

/**
 * Defines immutable wire-format constants for signed package manifests.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
object WasmlineManifestWireFormat {
    const val CURRENT_FORMAT_VERSION: Int = 1
    const val SIGNATURE_ALGORITHM: String = "Ed25519"
    const val SIGNATURE_SIZE_BYTES: Int = 64

    /** Active field numbers in [SignedManifestEnvelope]. */
    val envelopeFieldNumbers: Set<Int> = setOf(1, 2, 3, 5, 6)

    /** Field numbers retired from [SignedManifestEnvelope]. */
    val retiredEnvelopeFieldNumbers: Set<Int> = setOf(4)

    /** Active field numbers in [WasmlineManifest]. */
    val manifestFieldNumbers: Set<Int> = (1..11).toSet() + setOf(13, 14, 15)

    /** Field numbers retired from [WasmlineManifest]. */
    val retiredManifestFieldNumbers: Set<Int> = setOf(12)

    /** Active field numbers in [WasmlineRuntimeContract]. */
    val runtimeContractFieldNumbers: Set<Int> = (1..5).toSet()

    /** Active field numbers in [WasmlineAotCompatibilityProfile]. */
    val aotCompatibilityProfileFieldNumbers: Set<Int> = (1..5).toSet()

    /** Active field numbers in [WasmlineArtifactTarget]. */
    val artifactTargetFieldNumbers: Set<Int> = (1..6).toSet()

    /** Active field numbers in [WasmlineArtifactVariant]. */
    val artifactVariantFieldNumbers: Set<Int> = (1..3).toSet()

    /** Active field numbers in raw ABI metadata. */
    val rawAbiMetadataFieldNumbers: Set<Int> = (1..5).toSet()

    /** Active field numbers in raw ABI export declarations. */
    val rawExportFieldNumbers: Set<Int> = (1..3).toSet()

    /** Active field numbers in raw ABI import declarations. */
    val rawImportDeclarationFieldNumbers: Set<Int> = (1..3).toSet()

    /** Active field numbers in raw function signatures. */
    val rawFunctionSignatureFieldNumbers: Set<Int> = (1..2).toSet()
}

/**
 * Bounds manifest decoding and validation work before artifact selection.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
data class WasmlineManifestLimits(
    val maxManifestBytes: Int = DEFAULT_MAX_MANIFEST_BYTES,
    val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
    val maxStringBytes: Int = DEFAULT_MAX_STRING_BYTES,
    val maxMetadataEntries: Int = DEFAULT_MAX_METADATA_ENTRIES,
    val maxProfiles: Int = DEFAULT_MAX_PROFILES,
    val maxTargets: Int = DEFAULT_MAX_TARGETS,
    val maxVariantsPerTarget: Int = DEFAULT_MAX_VARIANTS_PER_TARGET,
    val maxTotalVariants: Int = DEFAULT_MAX_TOTAL_VARIANTS,
    val maxProfileIdsPerVariant: Int = DEFAULT_MAX_PROFILE_IDS_PER_VARIANT,
    val maxRawAbiExports: Int = DEFAULT_MAX_RAW_ABI_EXPORTS,
    val maxRawAbiImports: Int = DEFAULT_MAX_RAW_ABI_IMPORTS,
    val maxRawFunctionParameters: Int = DEFAULT_MAX_RAW_FUNCTION_PARAMETERS,
    val maxRawFunctionResults: Int = DEFAULT_MAX_RAW_FUNCTION_RESULTS,
) {
    init {
        require(maxManifestBytes > 0) { "maxManifestBytes must be positive." }
        require(maxPayloadBytes > 0 && maxPayloadBytes <= maxManifestBytes) {
            "maxPayloadBytes must be positive and no greater than maxManifestBytes."
        }
        require(maxStringBytes > 0) { "maxStringBytes must be positive." }
        require(maxMetadataEntries >= 0) { "maxMetadataEntries must be non-negative." }
        require(maxProfiles > 0) { "maxProfiles must be positive." }
        require(maxTargets > 0) { "maxTargets must be positive." }
        require(maxVariantsPerTarget > 0) { "maxVariantsPerTarget must be positive." }
        require(maxTotalVariants > 0) { "maxTotalVariants must be positive." }
        require(maxProfileIdsPerVariant > 0) { "maxProfileIdsPerVariant must be positive." }
        require(maxRawAbiExports >= 0) { "maxRawAbiExports must be non-negative." }
        require(maxRawAbiImports >= 0) { "maxRawAbiImports must be non-negative." }
        require(maxRawFunctionParameters >= 0) { "maxRawFunctionParameters must be non-negative." }
        require(maxRawFunctionResults >= 0) { "maxRawFunctionResults must be non-negative." }
    }

    /**
     * Defines conservative defaults for untrusted manifest input.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    companion object {
        const val DEFAULT_MAX_MANIFEST_BYTES: Int = 2 * 1024 * 1024
        const val DEFAULT_MAX_PAYLOAD_BYTES: Int = 1 * 1024 * 1024
        const val DEFAULT_MAX_STRING_BYTES: Int = 16 * 1024
        const val DEFAULT_MAX_METADATA_ENTRIES: Int = 256
        const val DEFAULT_MAX_PROFILES: Int = 256
        const val DEFAULT_MAX_TARGETS: Int = 256
        const val DEFAULT_MAX_VARIANTS_PER_TARGET: Int = 256
        const val DEFAULT_MAX_TOTAL_VARIANTS: Int = 4096
        const val DEFAULT_MAX_PROFILE_IDS_PER_VARIANT: Int = 256
        const val DEFAULT_MAX_RAW_ABI_EXPORTS: Int = 1024
        const val DEFAULT_MAX_RAW_ABI_IMPORTS: Int = 1024
        const val DEFAULT_MAX_RAW_FUNCTION_PARAMETERS: Int = 1024
        const val DEFAULT_MAX_RAW_FUNCTION_RESULTS: Int = 1024
    }
}

/**
 * Implements signing, content addressing, canonical ordering, and strict manifest validation.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
object WasmlineManifestProtocol {
    private const val SIGNING_DOMAIN: String = "wasmline.manifest\u0000"

    /** Builds the exact domain-separated Ed25519 signing input. */
    fun signingMessage(formatVersion: Int, payload: ByteArray): ByteArray {
        require(formatVersion > 0) { "Manifest formatVersion must be positive." }
        val domain = SIGNING_DOMAIN.encodeToByteArray()
        return ByteArray(domain.size + UInt.SIZE_BYTES + payload.size).also { result ->
            domain.copyInto(result)
            val offset = domain.size
            result[offset] = (formatVersion ushr 24).toByte()
            result[offset + 1] = (formatVersion ushr 16).toByte()
            result[offset + 2] = (formatVersion ushr 8).toByte()
            result[offset + 3] = formatVersion.toByte()
            payload.copyInto(result, destinationOffset = offset + UInt.SIZE_BYTES)
        }
    }

    /** Resolves the immutable package-relative artifact path for a digest and format. */
    fun artifactRelativePath(sha256: String, format: WasmlineArtifactFormat): String {
        require(SHA256_PATTERN.matches(sha256)) { "Artifact SHA-256 must be 64 lowercase hexadecimal characters." }
        return "artifacts/sha256/${sha256.take(2)}/$sha256.${artifactExtension(format)}"
    }

    /** Returns the standard extension without a leading dot. */
    fun artifactExtension(format: WasmlineArtifactFormat): String = when (format) {
        WasmlineArtifactFormat.RAW_WASM -> "wasm"
        WasmlineArtifactFormat.CWASM -> "cwasm"
        WasmlineArtifactFormat.PWASM -> "pwasm"
    }

    /** Returns a deterministically ordered manifest without changing domain values. */
    fun canonicalize(manifest: WasmlineManifest): WasmlineManifest {
        val canonicalContract = manifest.runtimeContract.copy(
            contractMetadata = orderedMap(manifest.runtimeContract.contractMetadata),
            rawAbi = manifest.runtimeContract.rawAbi?.let(::canonicalizeRawAbi),
        )
        val canonicalTargets = manifest.artifactTargets
            .map { target ->
                val merged = target.variants
                    .groupBy { variant -> variant.sha256 to variant.sizeBytes }
                    .map { (identity, variants) ->
                        WasmlineArtifactVariant(
                            aotCompatibilityProfileIds = variants
                                .flatMap(WasmlineArtifactVariant::aotCompatibilityProfileIds)
                                .distinct()
                                .sorted(),
                            sha256 = identity.first,
                            sizeBytes = identity.second,
                        )
                    }
                    .sortedWith(compareBy({ it.aotCompatibilityProfileIds.joinToString("\u0000") }, { it.sha256 }))
                target.copy(variants = merged)
            }
            .sortedBy(::targetSortKey)
        return manifest.copy(
            metadata = orderedMap(manifest.metadata),
            runtimeContract = canonicalContract,
            aotCompatibilityProfiles = manifest.aotCompatibilityProfiles.sortedBy(WasmlineAotCompatibilityProfile::id),
            artifactTargets = canonicalTargets,
        )
    }

    /** Returns deterministically ordered raw ABI metadata without changing its domain values. */
    fun canonicalizeRawAbi(rawAbi: RawAbiMetadata): RawAbiMetadata = rawAbi.copy(
        exports = rawAbi.exports.sortedWith(compareBy({ it.name }, { it.kind.name })),
        imports = rawAbi.imports.sortedWith(compareBy({ it.module }, { it.name })),
        requiredFeatures = rawAbi.requiredFeatures.sortedBy(CoreWasmFeature::name).toSet(),
    )

    /** Validates raw ABI metadata against the signed-manifest resource limits. */
    fun rawAbiValidationError(rawAbi: RawAbiMetadata, limits: WasmlineManifestLimits = WasmlineManifestLimits()): String? {
        if (rawAbi.version > RawAbiMetadata.CURRENT_VERSION) {
            return "Unsupported raw ABI metadata version ${rawAbi.version}."
        }
        if (rawAbi.exports.size > limits.maxRawAbiExports) {
            return "runtimeContract.rawAbi contains more than ${limits.maxRawAbiExports} exports."
        }
        if (rawAbi.imports.size > limits.maxRawAbiImports) {
            return "runtimeContract.rawAbi contains more than ${limits.maxRawAbiImports} imports."
        }
        stringValidationError("runtimeContract.rawAbi.memoryExport", rawAbi.memoryExport, limits, allowNull = true)?.let {
            return it
        }
        rawAbi.exports.forEach { export ->
            stringValidationError("runtimeContract.rawAbi.exports.name", export.name, limits)?.let { return it }
            if (export.kind != crow.wasmline.RawExportKind.FUNCTION && export.signature != null) {
                return "Only function exports may declare a raw ABI signature."
            }
            export.signature?.let { signature ->
                rawFunctionSignatureValidationError("runtimeContract.rawAbi.exports[${export.name}]", signature, limits)?.let {
                    return it
                }
            }
        }
        rawAbi.imports.forEach { import ->
            stringValidationError("runtimeContract.rawAbi.imports.module", import.module, limits)?.let { return it }
            stringValidationError("runtimeContract.rawAbi.imports.name", import.name, limits)?.let { return it }
            rawFunctionSignatureValidationError(
                "runtimeContract.rawAbi.imports[${import.module}.${import.name}]",
                import.signature,
                limits,
            )?.let { return it }
        }
        return null
    }

    /** Validates an envelope before signature verification or payload decoding. */
    fun envelopeValidationError(envelope: SignedManifestEnvelope, limits: WasmlineManifestLimits = WasmlineManifestLimits()): String? {
        if (envelope.formatVersion != WasmlineManifestWireFormat.CURRENT_FORMAT_VERSION) {
            return "Unsupported manifest format version ${envelope.formatVersion}."
        }
        if (envelope.algorithm != WasmlineManifestWireFormat.SIGNATURE_ALGORITHM) {
            return "Manifest signature algorithm must be ${WasmlineManifestWireFormat.SIGNATURE_ALGORITHM}."
        }
        if (envelope.signature.size != WasmlineManifestWireFormat.SIGNATURE_SIZE_BYTES) {
            return "Manifest Ed25519 signature must contain ${WasmlineManifestWireFormat.SIGNATURE_SIZE_BYTES} bytes."
        }
        if (envelope.payload.isEmpty()) return "Manifest payload must not be empty."
        if (envelope.payload.size > limits.maxPayloadBytes) {
            return "Manifest payload exceeds the configured ${limits.maxPayloadBytes}-byte limit."
        }
        return stringValidationError("publicKeyId", envelope.publicKeyId, limits, allowNull = true)
    }

    /** Validates all decoded manifest relationships and canonical protocol values. */
    fun validationError(
        manifest: WasmlineManifest,
        limits: WasmlineManifestLimits = WasmlineManifestLimits(),
        requireCanonicalOrder: Boolean = true,
    ): String? {
        stringValidationError("pluginId", manifest.pluginId, limits)?.let { return it }
        stringValidationError("version", manifest.version, limits)?.let { return it }
        stringValidationError("minSdkVersion", manifest.minSdkVersion, limits)?.let { return it }
        stringValidationError("displayName", manifest.displayName, limits, allowNull = true)?.let { return it }
        stringValidationError("author", manifest.author, limits, allowNull = true)?.let { return it }
        stringValidationError("description", manifest.description, limits, allowNull = true)?.let { return it }
        stringValidationError("iconUrl", manifest.iconUrl, limits, allowNull = true)?.let { return it }
        stringValidationError("homePageUrl", manifest.homePageUrl, limits, allowNull = true)?.let { return it }
        if (manifest.versionCode < 0) return "Manifest versionCode must be non-negative."
        if (manifest.buildTimestamp < 0) return "Manifest buildTimestamp must be non-negative."
        metadataValidationError("metadata", manifest.metadata, limits)?.let { return it }
        runtimeContractValidationError(manifest.runtimeContract, limits)?.let { return it }
        if (manifest.aotCompatibilityProfiles.size > limits.maxProfiles) {
            return "Manifest contains more than ${limits.maxProfiles} AOT compatibility profiles."
        }
        if (manifest.artifactTargets.isEmpty()) return "Manifest must contain at least one artifact target."
        if (manifest.artifactTargets.size > limits.maxTargets) {
            return "Manifest contains more than ${limits.maxTargets} artifact targets."
        }

        val profilesById = mutableMapOf<String, WasmlineAotCompatibilityProfile>()
        manifest.aotCompatibilityProfiles.forEach { profile ->
            profileValidationError(profile, limits)?.let { return it }
            if (profilesById.put(profile.id, profile) != null) {
                return "Manifest contains duplicate AOT compatibility profile '${profile.id}'."
            }
        }

        val targetKeys = mutableSetOf<String>()
        var totalVariants = 0
        manifest.artifactTargets.forEach { target ->
            targetValidationError(target, manifest.runtimeContract, profilesById, limits)?.let { return it }
            val targetKey = targetSortKey(target)
            if (!targetKeys.add(targetKey)) return "Manifest contains duplicate artifact target '$targetKey'."
            totalVariants += target.variants.size
            if (totalVariants > limits.maxTotalVariants) {
                return "Manifest contains more than ${limits.maxTotalVariants} artifact variants."
            }
        }

        if (requireCanonicalOrder) canonicalOrderValidationError(manifest)?.let { return it }
        return null
    }

    private fun runtimeContractValidationError(contract: WasmlineRuntimeContract, limits: WasmlineManifestLimits): String? {
        stringValidationError("runtimeContract.exportName", contract.exportName, limits, allowNull = true)?.let { return it }
        metadataValidationError("runtimeContract.contractMetadata", contract.contractMetadata, limits)?.let { return it }
        if (contract.rawAbi != null && contract.invocationProtocol != WasmlineInvocationProtocol.RAW_EXPORT) {
            return "runtimeContract.rawAbi requires invocationProtocol=RAW_EXPORT."
        }
        contract.rawAbi?.let { rawAbi -> rawAbiValidationError(rawAbi, limits)?.let { return it } }
        return when (contract.executionModel) {
            WasmlineExecutionModel.CORE_WASM -> when (contract.invocationProtocol) {
                WasmlineInvocationProtocol.WASMLINE_SERVICE,
                WasmlineInvocationProtocol.RAW_EXPORT,
                -> null

                WasmlineInvocationProtocol.COMPONENT_EXPORT -> "COMPONENT_EXPORT requires COMPONENT_MODEL."
            }

            WasmlineExecutionModel.COMPONENT_MODEL -> when (contract.invocationProtocol) {
                WasmlineInvocationProtocol.COMPONENT_EXPORT,
                WasmlineInvocationProtocol.WASMLINE_SERVICE,
                -> null

                WasmlineInvocationProtocol.RAW_EXPORT -> "COMPONENT_MODEL cannot use RAW_EXPORT."
            }
        }
    }

    private fun rawFunctionSignatureValidationError(
        field: String,
        signature: crow.wasmline.RawFunctionSignature,
        limits: WasmlineManifestLimits,
    ): String? = when {
        signature.parameters.size > limits.maxRawFunctionParameters ->
            "$field contains more than ${limits.maxRawFunctionParameters} parameters."

        signature.results.size > limits.maxRawFunctionResults ->
            "$field contains more than ${limits.maxRawFunctionResults} results."

        else -> null
    }

    private fun profileValidationError(profile: WasmlineAotCompatibilityProfile, limits: WasmlineManifestLimits): String? {
        stringValidationError("aotCompatibilityProfiles.id", profile.id, limits)?.let { return it }
        if (!PROFILE_ID_PATTERN.matches(profile.id)) {
            return "AOT compatibility profile ID '${profile.id}' must use sha256:<lowercase-digest>."
        }
        stringValidationError("aotCompatibilityProfiles.wasmtimeVersion", profile.wasmtimeVersion, limits)?.let {
            return it
        }
        if (!SEMANTIC_VERSION_PATTERN.matches(profile.wasmtimeVersion)) {
            return "AOT profile '${profile.id}' has invalid Wasmtime version '${profile.wasmtimeVersion}'."
        }
        stringValidationError(
            "aotCompatibilityProfiles.wasmtimeDistributionVersion",
            profile.wasmtimeDistributionVersion,
            limits,
        )?.let { return it }
        val distributionVersion = DISTRIBUTION_VERSION_PATTERN.matchEntire(profile.wasmtimeDistributionVersion)
            ?: return "AOT profile '${profile.id}' has invalid Wasmtime distribution version " +
                "'${profile.wasmtimeDistributionVersion}'; expected x.y.z.d."
        if (distributionVersion.groupValues[1] != profile.wasmtimeVersion) {
            return "AOT profile '${profile.id}' Wasmtime distribution version must extend " +
                "Wasmtime version '${profile.wasmtimeVersion}'."
        }
        if (profile.compileProfileSchemaVersion <= 0) {
            return "AOT profile '${profile.id}' compileProfileSchemaVersion must be positive."
        }
        return null
    }

    private fun targetValidationError(
        target: WasmlineArtifactTarget,
        contract: WasmlineRuntimeContract,
        profilesById: Map<String, WasmlineAotCompatibilityProfile>,
        limits: WasmlineManifestLimits,
    ): String? {
        stringValidationError("artifactTargets.operatingSystem", target.operatingSystem, limits, allowNull = true)?.let {
            return it
        }
        stringValidationError("artifactTargets.architecture", target.architecture, limits, allowNull = true)?.let {
            return it
        }
        stringValidationError(
            "artifactTargets.cpuFeatureProfile",
            target.cpuFeatureProfile,
            limits,
            allowNull = true,
        )?.let { return it }
        if (target.variants.isEmpty()) return "Artifact target '${targetSortKey(target)}' must contain a variant."
        if (target.variants.size > limits.maxVariantsPerTarget) {
            return "Artifact target '${targetSortKey(target)}' exceeds the ${limits.maxVariantsPerTarget}-variant limit."
        }
        val expectedBackend = when (target.format) {
            WasmlineArtifactFormat.RAW_WASM -> null
            WasmlineArtifactFormat.CWASM -> WasmlineEngineKind.CRANELIFT
            WasmlineArtifactFormat.PWASM -> WasmlineEngineKind.PULLEY
        }
        targetShapeValidationError(target, contract)?.let { return it }

        val referencedProfiles = mutableSetOf<String>()
        val digests = mutableSetOf<String>()
        target.variants.forEach { variant ->
            stringValidationError("artifactTargets.variants.sha256", variant.sha256, limits)?.let { return it }
            if (!SHA256_PATTERN.matches(variant.sha256)) {
                return "Artifact variant SHA-256 '${variant.sha256}' must contain 64 lowercase hexadecimal characters."
            }
            if (variant.sizeBytes <= 0) return "Artifact variant '${variant.sha256}' sizeBytes must be positive."
            if (!digests.add(variant.sha256)) {
                return "Artifact target '${targetSortKey(target)}' must merge variants with digest '${variant.sha256}'."
            }
            if (variant.aotCompatibilityProfileIds.size > limits.maxProfileIdsPerVariant) {
                return "Artifact variant '${variant.sha256}' exceeds the profile-reference limit."
            }
            if (expectedBackend == null && variant.aotCompatibilityProfileIds.isNotEmpty()) {
                return "RAW_WASM artifact variants must not reference AOT compatibility profiles."
            }
            if (expectedBackend != null && variant.aotCompatibilityProfileIds.isEmpty()) {
                return "${target.format} artifact variants must reference at least one AOT compatibility profile."
            }
            variant.aotCompatibilityProfileIds.forEach { profileId ->
                stringValidationError("artifactTargets.variants.aotCompatibilityProfileIds", profileId, limits)?.let {
                    return it
                }
                if (!referencedProfiles.add(profileId)) {
                    return "Artifact target '${targetSortKey(target)}' references profile '$profileId' more than once."
                }
                val profile = profilesById[profileId]
                    ?: return "Artifact variant '${variant.sha256}' references unknown profile '$profileId'."
                if (profile.artifactBackend != expectedBackend) {
                    return "${target.format} artifact variant '${variant.sha256}' references " +
                        "${profile.artifactBackend} profile '$profileId'."
                }
            }
        }
        return null
    }

    private fun canonicalOrderValidationError(manifest: WasmlineManifest): String? {
        val canonical = canonicalize(manifest)
        val rawAbi = manifest.runtimeContract.rawAbi
        val canonicalRawAbi = canonical.runtimeContract.rawAbi
        val usesCanonicalOrder =
            manifest.metadata.entries.toList() == canonical.metadata.entries.toList() &&
                manifest.runtimeContract.contractMetadata.entries.toList() ==
                canonical.runtimeContract.contractMetadata.entries.toList() &&
                rawAbi?.exports == canonicalRawAbi?.exports &&
                rawAbi?.imports == canonicalRawAbi?.imports &&
                rawAbi?.requiredFeatures?.toList() == canonicalRawAbi?.requiredFeatures?.toList() &&
                manifest.aotCompatibilityProfiles == canonical.aotCompatibilityProfiles &&
                manifest.artifactTargets == canonical.artifactTargets
        return if (usesCanonicalOrder) null else "Manifest collections and metadata must use canonical ordering."
    }

    private fun targetShapeValidationError(target: WasmlineArtifactTarget, contract: WasmlineRuntimeContract): String? =
        when (target.format) {
            WasmlineArtifactFormat.RAW_WASM -> when {
                contract.executionModel != WasmlineExecutionModel.CORE_WASM ->
                    "Published RAW_WASM targets require executionModel=CORE_WASM."

                target.operatingSystem != null -> "RAW_WASM target operatingSystem must be absent."

                target.architecture != "wasm32" -> "RAW_WASM target architecture must be wasm32."

                target.pointerWidth != 32 -> "RAW_WASM target pointerWidth must be 32."

                target.cpuFeatureProfile != null -> "RAW_WASM target cpuFeatureProfile must be absent."

                target.variants.size != 1 -> "RAW_WASM target must contain exactly one variant."

                else -> null
            }

            WasmlineArtifactFormat.CWASM -> when {
                target.operatingSystem !in NATIVE_OPERATING_SYSTEMS ->
                    "CWASM target operatingSystem must use a supported canonical value."

                target.operatingSystem == "ios" -> "iOS targets must use pulley64 PWASM."

                target.architecture !in NATIVE_ARCHITECTURES ->
                    "CWASM target architecture must use a supported canonical value."

                target.pointerWidth !in setOf(32, 64) -> "CWASM target pointerWidth must be 32 or 64."

                target.pointerWidth != expectedPointerWidth(target.architecture) ->
                    "CWASM target architecture and pointerWidth are inconsistent."

                target.cpuFeatureProfile.isNullOrBlank() -> "CWASM target cpuFeatureProfile must not be blank."

                else -> null
            }

            WasmlineArtifactFormat.PWASM -> when {
                target.operatingSystem != null -> "PWASM target operatingSystem must be absent."

                target.architecture !in setOf("pulley32", "pulley64") ->
                    "PWASM target architecture must be pulley32 or pulley64."

                target.pointerWidth != expectedPointerWidth(target.architecture) ->
                    "PWASM target architecture and pointerWidth are inconsistent."

                target.cpuFeatureProfile != null -> "PWASM target cpuFeatureProfile must be absent."

                else -> null
            }
        }

    private fun metadataValidationError(field: String, metadata: Map<String, String>, limits: WasmlineManifestLimits): String? {
        if (metadata.size > limits.maxMetadataEntries) {
            return "$field contains more than ${limits.maxMetadataEntries} entries."
        }
        metadata.forEach { (key, value) ->
            stringValidationError("$field key", key, limits)?.let { return it }
            stringValidationError("$field[$key]", value, limits, allowEmpty = true)?.let { return it }
        }
        return null
    }

    private fun stringValidationError(
        field: String,
        value: String?,
        limits: WasmlineManifestLimits,
        allowNull: Boolean = false,
        allowEmpty: Boolean = false,
    ): String? {
        if (value == null) return if (allowNull) null else "$field must be present."
        if (!allowEmpty && value.isBlank()) return "$field must not be blank."
        if (value.encodeToByteArray().size > limits.maxStringBytes) {
            return "$field exceeds the configured ${limits.maxStringBytes}-byte string limit."
        }
        return null
    }

    private fun orderedMap(source: Map<String, String>): Map<String, String> =
        source.entries.sortedBy(Map.Entry<String, String>::key).associate { it.key to it.value }

    private fun targetSortKey(target: WasmlineArtifactTarget): String = listOf(
        target.format.name,
        target.operatingSystem.orEmpty(),
        target.architecture.orEmpty(),
        target.pointerWidth?.toString().orEmpty(),
        target.cpuFeatureProfile.orEmpty(),
    ).joinToString("\u0000")

    private fun expectedPointerWidth(architecture: String?): Int? = when (architecture) {
        "x86", "pulley32", "wasm32" -> 32
        "aarch64", "x86_64", "riscv64", "pulley64" -> 64
        else -> null
    }

    private val NATIVE_OPERATING_SYSTEMS: Set<String> = setOf("android", "ios", "linux", "macos", "windows")
    private val NATIVE_ARCHITECTURES: Set<String> = setOf("aarch64", "x86", "x86_64", "riscv64")
    private val SHA256_PATTERN: Regex = Regex("^[0-9a-f]{64}$")
    private val PROFILE_ID_PATTERN: Regex = Regex("^sha256:[0-9a-f]{64}$")
    private val SEMANTIC_VERSION_PATTERN: Regex = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
    private val DISTRIBUTION_VERSION_PATTERN: Regex = Regex("^([0-9]+\\.[0-9]+\\.[0-9]+)\\.[1-9][0-9]*$")
}
