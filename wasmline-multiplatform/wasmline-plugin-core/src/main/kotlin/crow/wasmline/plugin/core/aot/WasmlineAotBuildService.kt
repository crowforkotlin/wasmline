package crow.wasmline.plugin.core.aot

import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.loader.model.WasmlineArtifactTarget
import crow.wasmline.loader.model.WasmlineArtifactVariant
import crow.wasmline.loader.model.WasmlineManifestProtocol
import crow.wasmline.loader.model.WasmlineRuntimeContract
import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import crow.wasmline.plugin.core.toolchain.FileDigest
import crow.wasmline.plugin.core.util.PlatformDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.nio.file.Files
import kotlin.math.max

/**
 * Defines one complete multi-profile AOT build request shared by Gradle and CLI adapters.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
data class WasmlineAotBuildRequest(
    val inputFile: File,
    val packageDirectory: File,
    val workingDirectory: File,
    val runtimeContract: WasmlineRuntimeContract,
    val targets: Collection<String>,
    val wasmtimeVersions: Collection<String> = emptyList(),
    val aotCompatibilityProfileIds: Collection<String> = emptyList(),
    val publishRawWasm: Boolean,
    val compilerCacheDirectory: File = defaultCompilerCacheDirectory(),
    val buildHost: String = PlatformDetector.detectPlatform(),
    val autoDownload: Boolean = false,
    val githubToken: String? = null,
    val maxParallelCompilations: Int = max(1, Runtime.getRuntime().availableProcessors() / 2),
    val logger: (String) -> Unit = {},
) {
    init {
        require(inputFile.isFile && inputFile.length() > 0) { "AOT input does not exist or is empty: ${inputFile.absolutePath}" }
        require(maxParallelCompilations > 0) { "maxParallelCompilations must be positive." }
        require(!publishRawWasm || runtimeContract.executionModel == WasmlineExecutionModel.CORE_WASM) {
            "Only Core Wasm builds may publish RAW_WASM."
        }
        val contractError = WasmlineArtifactDescriptor(
            path = inputFile.absolutePath,
            executionModel = runtimeContract.executionModel,
            invocationProtocol = runtimeContract.invocationProtocol,
            exportName = runtimeContract.exportName,
            contractMetadata = runtimeContract.contractMetadata,
            rawAbi = runtimeContract.rawAbi,
        ).validationError()
        require(contractError == null) { "Invalid AOT runtime contract: $contractError" }
        runtimeContract.rawAbi?.let { rawAbi ->
            WasmlineManifestProtocol.rawAbiValidationError(rawAbi)?.let { error ->
                throw IllegalArgumentException("Invalid AOT raw ABI metadata: $error")
            }
        }
    }

    /**
     * Resolves standard build request defaults.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    companion object {
        /** Returns the standard content-addressed compiler cache root. */
        fun defaultCompilerCacheDirectory(): File = File(
            System.getProperty("user.home"),
            ".wasmline/toolchains/wasmtime/compiler-assets",
        )
    }
}

/**
 * Expands profiles and targets, compiles bounded parallel units, and aggregates one build record.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
class WasmlineAotBuildService {
    /** Builds the complete profile matrix or fails before returning a signable record. */
    suspend fun build(request: WasmlineAotBuildRequest): WasmlineAotBuildRecord {
        val targetSpecs = WasmlineArtifactTargetFactory.create(request.targets)
        val backends = targetSpecs.map(WasmlineAotTargetSpec::artifactBackend).toSet()
        val profiles = AotCompatibilityCatalog.resolveProfiles(
            wasmtimeVersions = request.wasmtimeVersions,
            profileIds = request.aotCompatibilityProfileIds,
            artifactBackends = backends,
        )
        val options = WasmlineAotCompileOptions()
        profiles.forEach { profile ->
            require(profile.engineConfigurationProfile == options.canonicalDescriptor()) {
                "AOT profile '${profile.id}' does not use the frozen Wasmline compile options."
            }
        }

        check(request.packageDirectory.isDirectory || request.packageDirectory.mkdirs()) {
            "Unable to create AOT package directory: ${request.packageDirectory.absolutePath}"
        }
        check(request.workingDirectory.isDirectory || request.workingDirectory.mkdirs()) {
            "Unable to create AOT working directory: ${request.workingDirectory.absolutePath}"
        }
        val unitDirectory = Files.createTempDirectory(request.workingDirectory.toPath(), "matrix-").toFile()
        val contentStore = WasmlineContentAddressedStore(request.packageDirectory)
        try {
            val compilers = AotCompilerResolver(AotCompilerCache(request.compilerCacheDirectory)).use { resolver ->
                resolver.resolveAll(
                    profiles = profiles,
                    buildHost = request.buildHost,
                    autoDownload = request.autoDownload,
                    githubToken = request.githubToken,
                )
            }
            val compiler = WasmtimeCompiler(logger = request.logger)
            profiles.forEach { profile -> compiler.verify(compilers.getValue(profile.id).executable, profile) }
            val outputs = compileMatrix(
                request = request,
                targetSpecs = targetSpecs,
                profiles = profiles,
                compilers = compilers,
                compiler = compiler,
                options = options,
                unitDirectory = unitDirectory,
                contentStore = contentStore,
            ).toMutableList()
            if (request.publishRawWasm) outputs += storeRawWasm(request, contentStore)
            val orderedOutputs = outputs.sortedWith(COMPILED_ARTIFACT_ORDER)
            val artifactTargets = aggregateWasmlineArtifactTargets(orderedOutputs)
            val provenance = profiles.map { profile ->
                val resolved = compilers.getValue(profile.id)
                WasmlineAotCompilerProvenance(
                    profileId = profile.id,
                    artifactBackend = profile.artifactBackend,
                    wasmtimeVersion = profile.wasmtimeVersion,
                    wasmtimeDistributionVersion = profile.wasmtimeDistributionVersion,
                    buildHost = resolved.asset.buildHost,
                    compilerArchiveSha256 = resolved.asset.archiveSha256,
                    compilerExecutableSha256 = resolved.asset.executableSha256,
                )
            }.sortedBy(WasmlineAotCompilerProvenance::profileId)
            return WasmlineAotBuildRecord(
                inputFile = request.inputFile.name,
                inputSha256 = FileDigest.sha256Hex(request.inputFile),
                runtimeContract = request.runtimeContract,
                resolvedProfiles = profiles,
                requestedTargets = targetSpecs.map(WasmlineAotTargetSpec::requestedTarget),
                compiledOutputs = orderedOutputs,
                compilerProvenance = provenance,
                compileOptions = options,
                artifactTargets = artifactTargets,
            )
        } finally {
            unitDirectory.deleteRecursively()
        }
    }

    private suspend fun compileMatrix(
        request: WasmlineAotBuildRequest,
        targetSpecs: List<WasmlineAotTargetSpec>,
        profiles: List<AotCompatibilityProfileSpec>,
        compilers: Map<String, ResolvedAotCompiler>,
        compiler: WasmtimeCompiler,
        options: WasmlineAotCompileOptions,
        unitDirectory: File,
        contentStore: WasmlineContentAddressedStore,
    ): List<WasmlineCompiledArtifact> = coroutineScope {
        val units = planWasmlineAotBuildUnits(targetSpecs, profiles)
        val semaphore = Semaphore(request.maxParallelCompilations)
        units.mapIndexed { index, (target, profile) ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val extension = WasmlineManifestProtocol.artifactExtension(target.format)
                    val output = File(unitDirectory, "unit-${index.toString().padStart(4, '0')}.$extension")
                    compiler.compile(
                        executable = compilers.getValue(profile.id).executable,
                        inputFile = request.inputFile,
                        outputFile = output,
                        normalizedTarget = target.normalizedTarget,
                        options = options,
                    )
                    val stored = contentStore.put(output, target.format)
                    target.toCompiledArtifact(profile.id, stored)
                }
            }
        }.awaitAll()
    }

    private fun storeRawWasm(request: WasmlineAotBuildRequest, contentStore: WasmlineContentAddressedStore): WasmlineCompiledArtifact {
        val stored = contentStore.put(request.inputFile, WasmlineArtifactFormat.RAW_WASM)
        return WasmlineCompiledArtifact(
            requestedTarget = "wasm32",
            normalizedTarget = "wasm32",
            format = WasmlineArtifactFormat.RAW_WASM,
            architecture = "wasm32",
            pointerWidth = 32,
            sha256 = stored.sha256,
            sizeBytes = stored.sizeBytes,
            contentRelativePath = stored.relativePath,
        )
    }

    private fun WasmlineAotTargetSpec.toCompiledArtifact(profileId: String, stored: StoredWasmlineArtifact): WasmlineCompiledArtifact =
        WasmlineCompiledArtifact(
            requestedTarget = requestedTarget,
            normalizedTarget = normalizedTarget,
            format = format,
            artifactBackend = artifactBackend,
            aotCompatibilityProfileId = profileId,
            operatingSystem = operatingSystem,
            architecture = architecture,
            pointerWidth = pointerWidth,
            cpuFeatureProfile = cpuFeatureProfile,
            sha256 = stored.sha256,
            sizeBytes = stored.sizeBytes,
            contentRelativePath = stored.relativePath,
        )

    /**
     * Defines deterministic ordering for compiled matrix outputs.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    private companion object {
        val COMPILED_ARTIFACT_ORDER: Comparator<WasmlineCompiledArtifact> =
            compareBy<WasmlineCompiledArtifact>(
                { wasmlineAotTargetKey(it) },
                { it.aotCompatibilityProfileId.orEmpty() },
                { it.sha256 },
            )
    }
}

/** Plans only target and profile pairs that use the same artifact backend. */
internal fun planWasmlineAotBuildUnits(
    targets: List<WasmlineAotTargetSpec>,
    profiles: List<AotCompatibilityProfileSpec>,
): List<Pair<WasmlineAotTargetSpec, AotCompatibilityProfileSpec>> {
    require(targets.isNotEmpty()) { "At least one AOT target is required." }
    require(profiles.isNotEmpty()) { "At least one AOT compatibility profile is required." }
    targets.forEach { target ->
        require(profiles.any { it.artifactBackend == target.artifactBackend }) {
            "AOT target '${target.requestedTarget}' has no ${target.artifactBackend} compatibility profile."
        }
    }
    return targets.flatMap { target ->
        profiles
            .filter { it.artifactBackend == target.artifactBackend }
            .map { profile -> target to profile }
    }
}

/** Aggregates physical outputs into fixed targets and content-addressed profile variants. */
internal fun aggregateWasmlineArtifactTargets(outputs: List<WasmlineCompiledArtifact>): List<WasmlineArtifactTarget> = outputs
    .groupBy(::wasmlineAotTargetKey)
    .map { (_, targetOutputs) ->
        val first = targetOutputs.first()
        val variants = targetOutputs
            .groupBy { output -> output.sha256 to output.sizeBytes }
            .map { (contentIdentity, contentOutputs) ->
                WasmlineArtifactVariant(
                    aotCompatibilityProfileIds = contentOutputs
                        .mapNotNull(WasmlineCompiledArtifact::aotCompatibilityProfileId)
                        .distinct()
                        .sorted(),
                    sha256 = contentIdentity.first,
                    sizeBytes = contentIdentity.second,
                )
            }
            .sortedWith(compareBy({ it.aotCompatibilityProfileIds.joinToString("\u0000") }, { it.sha256 }))
        WasmlineArtifactTarget(
            format = first.format,
            operatingSystem = first.operatingSystem,
            architecture = first.architecture,
            pointerWidth = first.pointerWidth,
            cpuFeatureProfile = first.cpuFeatureProfile,
            variants = variants,
        )
    }
    .sortedBy { target ->
        listOf(
            target.format.name,
            target.operatingSystem.orEmpty(),
            target.architecture.orEmpty(),
            target.pointerWidth?.toString().orEmpty(),
            target.cpuFeatureProfile.orEmpty(),
        ).joinToString("\u0000")
    }

private fun wasmlineAotTargetKey(output: WasmlineCompiledArtifact): String = listOf(
    output.format.name,
    output.operatingSystem.orEmpty(),
    output.architecture,
    output.pointerWidth.toString(),
    output.cpuFeatureProfile.orEmpty(),
).joinToString("\u0000")
