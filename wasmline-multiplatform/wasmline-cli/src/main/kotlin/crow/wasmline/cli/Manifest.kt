package crow.wasmline.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.unique
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.long
import crow.wasmline.plugin.core.aot.WasmlineAotBuildRecords
import crow.wasmline.plugin.core.manifest.ManifestSigner
import crow.wasmline.plugin.core.manifest.WasmlineManifestSigningRequest
import java.io.File

/**
 * Signs a complete unified AOT build record without changing its runtime contract.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
internal class Manifest : CliktCommand(name = "manifest") {
    private val directory by option("-d", "--dir")
        .file(mustExist = true, canBeFile = false, canBeDir = true)
        .required()
    private val pluginId by option("--plugin-id")
    private val version by option("--version").default("1.0.0")
    private val versionCode by option("--version-code").long().default(1L)
    private val minSdkVersion by option("--min-sdk").default(BuildConfig.VERSION)
    private val buildTimestamp by option("--build-timestamp").long().default(0L)
    private val displayName by option("--display-name")
    private val author by option("--author")
    private val description by option("--description")
    private val iconUrl by option("--icon-url")
    private val homeUrl by option("--home-url")
    private val metadata by option("--metadata").multiple().unique()
    private val key by option("-k", "--key").required().help("Ed25519 private key: file path or hex string")

    override fun run() {
        try {
            val recordFile = File(directory, WasmlineAotBuildRecords.FILE_NAME)
            val record = WasmlineAotBuildRecords.read(recordFile)
            ManifestSigner().createSignedManifest(
                WasmlineManifestSigningRequest(
                    buildRecord = record,
                    pluginId = pluginId ?: File(record.inputFile).nameWithoutExtension,
                    version = version,
                    versionCode = versionCode,
                    minSdkVersion = minSdkVersion,
                    buildTimestamp = buildTimestamp,
                    signingKey = key,
                    outputDirectory = directory,
                    displayName = displayName,
                    author = author,
                    description = description,
                    iconUrl = iconUrl,
                    homePageUrl = homeUrl,
                    metadata = parseKeyValueEntries(metadata, "Metadata"),
                    logger = ::echo,
                ),
            )
        } catch (error: Exception) {
            echo("Error: ${error.message}", err = true)
            throw ProgramResult(1)
        }
    }
}
