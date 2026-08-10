package crow.wasmline.plugin.core.toolchain

import crow.wasmline.plugin.core.util.PlatformDetector

/**
 * Identifies a binary or data file used by the Component Model build pipeline.
 */
enum class WasmlineTool {
    WIT_BINDGEN,
    WASM_TOOLS,
    WASI_PREVIEW1_REACTOR_ADAPTER,
}

/** Describes how a locked tool asset is distributed. */
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
data class ToolAssetSpec(
    val tool: WasmlineTool,
    val version: String,
    val platform: String,
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
        require(archiveName.isNotBlank()) { "Tool archive name must not be blank." }
        require(downloadUrl.startsWith("https://")) { "Tool download URL must use HTTPS." }
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Tool SHA-256 must contain 64 hexadecimal characters." }
        require(entryFileName.isNotBlank()) { "Tool entry file name must not be blank." }
    }

    /** Stable identity used by the local verification marker. */
    val fingerprint: String
        get() = listOf(tool.name, version, platform, archiveName, sha256.lowercase()).joinToString(":")
}

/**
 * Locked Component Model toolchain versions and release assets.
 *
 * Asset digests are copied from the GitHub release metadata for each pinned
 * release. Adding a platform requires both a URL and a digest.
 */
object ToolchainCatalog {
    const val WIT_BINDGEN_VERSION = "0.57.1"
    const val WASM_TOOLS_VERSION = "1.255.0"
    const val WASMTIME_VERSION = "47.0.2"
    const val WASI_PREVIEW1_ADAPTER_VERSION = WASMTIME_VERSION
    const val UNIVERSAL_PLATFORM = "universal"

    private const val WIT_BINDGEN_BASE =
        "https://github.com/crowforkotlin/wit-bindgen/releases/download/v$WIT_BINDGEN_VERSION"
    private const val WASM_TOOLS_BASE =
        "https://github.com/bytecodealliance/wasm-tools/releases/download/v$WASM_TOOLS_VERSION"
    private const val WASMTIME_BASE =
        "https://github.com/bytecodealliance/wasmtime/releases/download/v$WASI_PREVIEW1_ADAPTER_VERSION"

    private val assets: List<ToolAssetSpec> = listOf(
        witBindgen("aarch64-linux", "70e4177a8dedbb82a464716fa81abffab8f2e33def42b03a5e0ce69f19731319"),
        witBindgen("aarch64-macos", "e61cdd06d86fe39ed988d89324dda620753872a19aa94dca562262ef09e021b5"),
        witBindgen("aarch64-windows", "545cf586b853f3524c8344d369ea6365113b1a01b9fa26fef42f31c049027786"),
        witBindgen("riscv64gc-linux", "38b000ddbaf25fc4079fedf32b3e722a2b1af5ee0466261e73c2432e9abb6cea"),
        witBindgen("x86_64-linux", "f8a4e8f8f60a4b0eada43a722c94dba0bcb8a214ca1d35884c129b4279ef9dd0"),
        witBindgen("x86_64-macos", "30a7c5064bfb876f3397cb6d6235e87f5c6ead45477f6ad2ce105c9c90a7dbac"),
        witBindgen("x86_64-windows", "9425d143f9394b500c5827ba540e6d03926a454575fe51507a366bf1d04d7563"),
        wasmTools("aarch64-linux", "b51adcd4b7e2b85c689af3a1800534e7de192fdf47b1b6b6a8b5bcb0f449c392"),
        wasmTools("aarch64-macos", "58bf83fdfa59da2c70ac6eb8dd395870934d8e3af835ff9311f34b9072586547"),
        wasmTools("aarch64-musl", "807896f34c5d7b55721b4c682852d205860c98fce7459e9f31780c55bf059046"),
        wasmTools("aarch64-windows", "97ad81dd662d7c61b4f53dd88cba7cd0053b336ee3524e00269a1957b52398ab"),
        wasmTools("riscv64-linux", "cf72092ebdaf160656836973d9f0f20ae3083dcbb3f366cac90bf9436b7ed735"),
        wasmTools("wasm32-wasip1", "231f72d0be8b6a8b5d7bb5e8a25d1c0f55a544e650e98c9d0e2218511c94841c"),
        wasmTools("x86_64-linux", "a62237f4731c45f665f1115cad39acaeec02963cbc848c9473ab033eed837072"),
        wasmTools("x86_64-macos", "21f0d003c5a937f29fe4cbbcb947b41ed7cc14982b8680abb15ba4078cb6a227"),
        wasmTools("x86_64-musl", "ba6a735fb6860dfe7938991d67dfe65dfa691989111ffa5e96cd90532c5f3c5a"),
        wasmTools("x86_64-windows", "04380e13369d7282af0bfc7f34e3e2958b5f3bbc260611a3dccebbc3c9b81cb1"),
        ToolAssetSpec(
            tool = WasmlineTool.WASI_PREVIEW1_REACTOR_ADAPTER,
            version = WASI_PREVIEW1_ADAPTER_VERSION,
            platform = UNIVERSAL_PLATFORM,
            archiveName = "wasi_snapshot_preview1.reactor.wasm",
            downloadUrl = "$WASMTIME_BASE/wasi_snapshot_preview1.reactor.wasm",
            sha256 = "928546f9b8f704e0e01e656a2c12f08f6e0da6f5b29da0179ee282a4138ef5c4",
            distribution = ToolDistribution.FILE,
            entryFileName = "wasi_snapshot_preview1.reactor.wasm",
            executable = false,
        ),
    )

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

    private fun witBindgen(platform: String, sha256: String): ToolAssetSpec {
        val windows = platform.endsWith("-windows")
        val extension = if (windows) "zip" else "tar.gz"
        val archiveName = "wit-bindgen-$WIT_BINDGEN_VERSION-$platform.$extension"
        return ToolAssetSpec(
            tool = WasmlineTool.WIT_BINDGEN,
            version = WIT_BINDGEN_VERSION,
            platform = platform,
            archiveName = archiveName,
            downloadUrl = "$WIT_BINDGEN_BASE/$archiveName",
            sha256 = sha256,
            distribution = if (windows) ToolDistribution.ZIP else ToolDistribution.TAR_GZ,
            entryFileName = if (windows) "wit-bindgen.exe" else "wit-bindgen",
            executable = true,
        )
    }

    private fun wasmTools(platform: String, sha256: String): ToolAssetSpec {
        val windows = platform.endsWith("-windows")
        val extension = if (windows) "zip" else "tar.gz"
        val archiveName = "wasm-tools-$WASM_TOOLS_VERSION-$platform.$extension"
        return ToolAssetSpec(
            tool = WasmlineTool.WASM_TOOLS,
            version = WASM_TOOLS_VERSION,
            platform = platform,
            archiveName = archiveName,
            downloadUrl = "$WASM_TOOLS_BASE/$archiveName",
            sha256 = sha256,
            distribution = if (windows) ToolDistribution.ZIP else ToolDistribution.TAR_GZ,
            entryFileName = if (windows) "wasm-tools.exe" else "wasm-tools",
            executable = true,
        )
    }
}
