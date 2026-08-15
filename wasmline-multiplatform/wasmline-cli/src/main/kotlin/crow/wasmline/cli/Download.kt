package crow.wasmline.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.unique
import com.github.ajalt.clikt.parameters.types.file
import crow.wasmline.plugin.core.download.WasmtimeDistribution
import crow.wasmline.plugin.core.download.WasmtimeDownloader
import crow.wasmline.plugin.core.util.PlatformDetector
import kotlinx.coroutines.runBlocking
import java.io.File

internal class Download : CliktCommand(name = "download") {

    private val downloadVersions by option("-v", "--versions")
        .multiple()
        .unique()
        .help("Versions to download, for example v${BuildConfig.WASMTIME_VERSION} or latest")

    private val archOption by option("-a", "--arch").help("Target platform; defaults to the current platform")

    private val outputDir by option("-o", "--output")
        .file(canBeFile = false, canBeDir = true)
        .default(File("build/wasmline/wasmtime"))
        .help("Directory for extracted wasmtime binaries")

    private val forceDownload by option("-f", "--force").flag(default = false)

    private val distributionName by option("--distribution")
        .default(DEFAULT_WASMTIME_DISTRIBUTION.name.lowercase())
        .help("Wasmtime asset kind: minimal (runtime) or full (build compiler)")

    private val showTokenStatus by option("--show-token-status", hidden = true).flag(default = false)

    override fun run() = runBlocking {
        if (showTokenStatus) {
            val authenticated = !System.getenv("GITHUB_TOKEN").isNullOrEmpty()
            echo("GitHub Token Status: ${if (authenticated) "Authenticated" else "Unauthenticated"}")
            throw ProgramResult(0)
        }

        val versions = downloadVersions.flatMap { it.split(',') }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .ifEmpty { listOf("latest") }
        val platform = archOption ?: PlatformDetector.detectPlatform()
        val distribution = parseWasmtimeDistribution(distributionName)
        outputDir.mkdirs()
        val downloader = WasmtimeDownloader()
        val failed = try {
            var failed = false
            versions.forEach { version ->
                runCatching {
                    downloader.download(
                        githubToken = System.getenv("GITHUB_TOKEN")?.takeIf(String::isNotEmpty),
                        version = version,
                        platform = platform,
                        distribution = distribution,
                        outputDir = outputDir,
                        force = forceDownload,
                    )
                }.onFailure {
                    failed = true
                    echo("Error: ${it.message}", err = true)
                }
            }
            failed
        } finally {
            downloader.close()
        }
        if (failed) throw ProgramResult(1)
    }
}

internal val DEFAULT_WASMTIME_DISTRIBUTION = WasmtimeDistribution.MINIMAL

internal fun parseWasmtimeDistribution(value: String): WasmtimeDistribution = when (value.trim().lowercase()) {
    "minimal" -> WasmtimeDistribution.MINIMAL
    "full" -> WasmtimeDistribution.FULL
    else -> error("Unknown Wasmtime distribution '$value'. Expected minimal or full.")
}

internal object DownloadPlatformDetector {
    fun detectPlatform(): String = PlatformDetector.detectPlatform()

    internal fun detectPlatform(osName: String, osArch: String, macHardwareArm64: Boolean? = null): String =
        PlatformDetector.detectPlatform(osName, osArch, macHardwareArm64)

    internal fun normalizeOs(osName: String): String = PlatformDetector.normalizeOs(osName)

    internal fun normalizeArch(osArch: String): String = PlatformDetector.normalizeArch(osArch)
}
