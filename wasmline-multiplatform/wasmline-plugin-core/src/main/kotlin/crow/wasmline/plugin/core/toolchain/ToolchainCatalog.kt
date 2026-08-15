package crow.wasmline.plugin.core.toolchain

import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import crow.wasmline.plugin.core.util.PlatformDetector
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Identifies a binary or data file used by the Component Model build pipeline.
 */

@InternalWasmlineToolingApi
enum class WasmlineTool {
    WIT_BINDGEN,
    WASM_TOOLS,
    WASI_PREVIEW1_REACTOR_ADAPTER,
}

/** Describes how a locked tool asset is distributed. */

@InternalWasmlineToolingApi
enum class ToolDistribution {
    TAR_GZ,
    ZIP,
    FILE,
}

/**
 * Describes one immutable release asset.
 *
 * The SHA-256 is mandatory so callers never trust a mutable latest download.
 */

@InternalWasmlineToolingApi
data class ToolAssetSpec(
    val tool: WasmlineTool,
    val version: String,
    val platform: String,
    val assetId: Long,
    val size: Long,
    val archiveName: String,
    val downloadUrl: String,
    val sha256: String,
    val distribution: ToolDistribution,
    val entryFileName: String,
    val executable: Boolean,
) {
    init {
        require(version.isNotBlank()) { "Tool version must not be blank." }
        require(platform.isNotBlank()) { "Tool platform must not be blank." }
        require(assetId > 0) { "Tool release asset ID must be positive." }
        require(size > 0) { "Tool release asset size must be positive." }
        require(archiveName.isNotBlank()) { "Tool archive name must not be blank." }
        require(downloadUrl.startsWith("https://")) { "Tool download URL must use HTTPS." }
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Tool SHA-256 must contain 64 hexadecimal characters." }
        require(entryFileName.isNotBlank()) { "Tool entry file name must not be blank." }
    }

    /** Stable identity used by the local verification marker. */
    val fingerprint: String
        get() = listOf(tool.name, version, platform, assetId, size, archiveName, sha256.lowercase()).joinToString(":")
}

/**
 * Locked Component Model toolchain versions and release assets loaded from the
 * generated resource managed by scripts/sync_version.py.
 */

@InternalWasmlineToolingApi
object ToolchainCatalog {
    const val UNIVERSAL_PLATFORM = "universal"

    private const val LOCK_RESOURCE = "META-INF/wasmline/toolchain/toolchain-lock.json"

    private val lock: LockedToolchain = loadLock()

    @JvmField
    val WIT_BINDGEN_VERSION: String = lock.versions.witBindgenVersion

    @JvmField
    val WASM_TOOLS_VERSION: String = lock.versions.wasmToolsVersion

    @JvmField
    val WASMTIME_VERSION: String = lock.versions.wasmtimeVersion

    @JvmField
    val WASI_PREVIEW1_ADAPTER_VERSION: String = WASMTIME_VERSION

    private val assets: List<ToolAssetSpec> = lock.toAssetSpecs()

    /** Returns the pinned default version for a tool. */
    fun defaultVersion(tool: WasmlineTool): String = when (tool) {
        WasmlineTool.WIT_BINDGEN -> WIT_BINDGEN_VERSION
        WasmlineTool.WASM_TOOLS -> WASM_TOOLS_VERSION
        WasmlineTool.WASI_PREVIEW1_REACTOR_ADAPTER -> WASI_PREVIEW1_ADAPTER_VERSION
    }

    /** Resolves a locked release asset for the requested host platform. */
    fun requireAsset(tool: WasmlineTool, version: String = defaultVersion(tool), platform: String = defaultPlatform(tool)): ToolAssetSpec {
        val normalizedVersion = version.removePrefix("v")
        val normalizedPlatform = normalizePlatform(tool, platform)
        return assets.firstOrNull {
            it.tool == tool && it.version == normalizedVersion && it.platform == normalizedPlatform
        } ?: error(
            "No locked " + tool.name.lowercase() + " asset for version " + normalizedVersion +
                " on " + normalizedPlatform + ".",
        )
    }

    /** Lists the locked assets, primarily for diagnostics and tests. */
    fun allAssets(): List<ToolAssetSpec> = assets.toList()

    private fun defaultPlatform(tool: WasmlineTool): String =
        if (tool == WasmlineTool.WASI_PREVIEW1_REACTOR_ADAPTER) UNIVERSAL_PLATFORM else PlatformDetector.detectPlatform()

    private fun normalizePlatform(tool: WasmlineTool, platform: String): String {
        if (tool == WasmlineTool.WASI_PREVIEW1_REACTOR_ADAPTER) return UNIVERSAL_PLATFORM
        return when {
            tool == WasmlineTool.WIT_BINDGEN && platform == "riscv64-linux" -> "riscv64gc-linux"
            tool == WasmlineTool.WASM_TOOLS && platform == "riscv64gc-linux" -> "riscv64-linux"
            else -> platform
        }
    }

    private fun loadLock(): LockedToolchain {
        val source = requireNotNull(ToolchainCatalog::class.java.classLoader.getResourceAsStream(LOCK_RESOURCE)) {
            "Missing packaged toolchain lock: $LOCK_RESOURCE."
        }
        val text = source.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val decoded = runCatching {
            Json.decodeFromString<LockedToolchain>(text)
        }.getOrElse { error ->
            throw IllegalStateException("Invalid packaged toolchain lock: ${error.message}", error)
        }
        decoded.validate()
        return decoded
    }
}

@Serializable
private data class LockedToolchain(
    val schemaVersion: Int,
    val generatedBy: String,
    val sourceManifest: String,
    val versions: LockedToolchainVersions,
    val tools: List<LockedTool>,
) {
    fun validate() {
        require(schemaVersion == 1) { "Unsupported toolchain lock schema: $schemaVersion." }
        require(generatedBy == "scripts/sync_version.py") { "Invalid toolchain lock generator: $generatedBy." }
        require(sourceManifest == "scripts/versions.json") { "Invalid toolchain lock source: $sourceManifest." }

        val lockedByTool = tools.associateBy { locked -> locked.tool }
        require(lockedByTool.size == tools.size) { "Toolchain lock contains duplicate tools." }
        require(lockedByTool.keys == WasmlineTool.entries.map(WasmlineTool::name).toSet()) {
            "Toolchain lock does not contain the required tools."
        }

        val policies = lockPolicies(versions)
        tools.forEach { tool -> tool.validate(requireNotNull(policies[tool.tool])) }
    }

    fun toAssetSpecs(): List<ToolAssetSpec> {
        validate()
        return tools.flatMap { tool -> tool.toAssetSpecs() }
    }
}

@Serializable
private data class LockedToolchainVersions(
    @SerialName("wit_bindgen_version")
    val witBindgenVersion: String,
    @SerialName("wasm_tools_version")
    val wasmToolsVersion: String,
    @SerialName("wasmtime_version")
    val wasmtimeVersion: String,
)

@Serializable
private data class LockedTool(
    val tool: String,
    val version: String,
    val versionKey: String,
    val repository: String,
    val releaseTag: String,
    val releaseId: Long,
    val assets: List<LockedAsset>,
) {
    fun validate(policy: LockedToolPolicy) {
        require(version == policy.version) { "Locked $tool version does not match ${policy.versionKey}." }
        require(versionKey == policy.versionKey) { "Locked $tool version key is invalid: $versionKey." }
        require(repository == policy.repository) { "Locked $tool repository is invalid: $repository." }
        require(releaseTag == "v$version") { "Locked $tool release tag is invalid: $releaseTag." }
        require(releaseId > 0) { "Locked $tool release ID must be positive." }

        val lockedByPlatform = assets.associateBy { asset -> asset.platform }
        require(lockedByPlatform.size == assets.size) { "Locked $tool contains duplicate platforms." }
        require(lockedByPlatform.keys == policy.platforms) { "Locked $tool platform set is invalid." }
    }

    fun toAssetSpecs(): List<ToolAssetSpec> {
        val parsedTool = runCatching { WasmlineTool.valueOf(tool) }.getOrElse { error ->
            throw IllegalArgumentException("Unknown locked tool: $tool.", error)
        }
        return assets.map { asset -> asset.toAssetSpec(parsedTool, version) }
    }
}

@Serializable
private data class LockedAsset(
    val platform: String,
    val assetId: Long,
    val size: Long,
    val updatedAt: String,
    val archiveName: String,
    val downloadUrl: String,
    val sha256: String,
    val distribution: String,
    val entryFileName: String,
    val executable: Boolean,
) {
    fun toAssetSpec(tool: WasmlineTool, version: String): ToolAssetSpec {
        require(updatedAt.isNotBlank()) { "Locked ${tool.name}/$platform update time must not be blank." }
        val parsedDistribution = runCatching { ToolDistribution.valueOf(distribution) }.getOrElse { error ->
            throw IllegalArgumentException("Unknown distribution for ${tool.name}/$platform: $distribution.", error)
        }
        return ToolAssetSpec(
            tool = tool,
            version = version,
            platform = platform,
            assetId = assetId,
            size = size,
            archiveName = archiveName,
            downloadUrl = downloadUrl,
            sha256 = sha256,
            distribution = parsedDistribution,
            entryFileName = entryFileName,
            executable = executable,
        )
    }
}

private data class LockedToolPolicy(val version: String, val versionKey: String, val repository: String, val platforms: Set<String>)

private fun lockPolicies(versions: LockedToolchainVersions): Map<String, LockedToolPolicy> = mapOf(
    WasmlineTool.WIT_BINDGEN.name to LockedToolPolicy(
        version = versions.witBindgenVersion,
        versionKey = "wit_bindgen_version",
        repository = "crowforkotlin/wit-bindgen",
        platforms = setOf(
            "aarch64-linux",
            "aarch64-macos",
            "aarch64-windows",
            "riscv64gc-linux",
            "x86_64-linux",
            "x86_64-macos",
            "x86_64-windows",
        ),
    ),
    WasmlineTool.WASM_TOOLS.name to LockedToolPolicy(
        version = versions.wasmToolsVersion,
        versionKey = "wasm_tools_version",
        repository = "bytecodealliance/wasm-tools",
        platforms = setOf(
            "aarch64-linux",
            "aarch64-macos",
            "aarch64-musl",
            "aarch64-windows",
            "riscv64-linux",
            "wasm32-wasip1",
            "x86_64-linux",
            "x86_64-macos",
            "x86_64-musl",
            "x86_64-windows",
        ),
    ),
    WasmlineTool.WASI_PREVIEW1_REACTOR_ADAPTER.name to LockedToolPolicy(
        version = versions.wasmtimeVersion,
        versionKey = "wasmtime_version",
        repository = "bytecodealliance/wasmtime",
        platforms = setOf(ToolchainCatalog.UNIVERSAL_PLATFORM),
    ),
)
