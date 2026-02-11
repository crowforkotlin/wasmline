@file:Suppress("SpellCheckingInspection", "INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(ExperimentalSerializationApi::class)

package com.mordecai.wasmline.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.unique
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.long
import com.mordecai.wasmline.cli.extensions.baseJson
import com.mordecai.wasmline.loader.internal.crypto.Ed25519
import crow.mordecai.wasmline.cli.BuildConfig
import crow.mordecai.wasmline.model.SignedManifestEnvelope
import crow.mordecai.wasmline.model.WasmlineManifest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Build command — orchestrates the full pipeline: compile → manifest → package.
 *
 * Output layout:
 * ```
 * build/wasmline/
 * ├── output/
 * │   └── {name}-{version}/
 * │       ├── manifest.wlm
 * │       ├── {name}-pulley64.pwasm
 * │       ├── {name}-aarch64-android.cwasm
 * │       └── debug/
 * │           ├── compile-result.json
 * │           └── manifest.json
 * └── dist/
 *     └── {name}-{version}.zip
 * ```
 *
 * 2026/2/12
 * @author crowforkotlin
 * @formatter:on
 */
class Build : CliktCommand(name = "build") {

    // ==================== Compile 参数 ====================

    private val inputFile by option("-i", "--input")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
        .required()
        .help("Input .wasm file path")

    private val name by option("-n", "--name")
        .help("Product name for output artifacts (e.g., manga). Default: input file name without extension")

    private val wasmtimeDir by option("-wt", "--wasmtime")
        .file(mustExist = true, canBeDir = true, canBeFile = false)
        .required()
        .help("Directory containing the wasmtime executable")

    private val targets by option("-a", "--arch")
        .multiple()
        .unique()
        .help("Target architectures. Default: all common targets")

    // ==================== Manifest 参数 ====================

    private val pluginId by option("--plugin-id")
        .help("Plugin unique identifier (e.g., com.mordecai.demo)")

    private val version by option("-v", "--version")
        .default("1.0.0")
        .help("Semantic version. Default: 1.0.0")

    private val versionCode by option("--version-code")
        .long()
        .default(1L)
        .help("Integer version code. Default: 1")

    private val minSdkVersion by option("--min-sdk")
        .default(BuildConfig.VERSION)
        .help("Minimum wasmline SDK version")

    private val displayName by option("--display-name")
        .help("Plugin display name")

    private val author by option("--author")
        .help("Plugin author")

    private val description by option("--description")
        .help("Plugin description")

    private val iconUrl by option("--icon-url")
        .help("Icon URL or relative path")

    private val homeUrl by option("--home-url")
        .help("Home page or repository URL")

    private val key by option("-k", "--key")
        .required()
        .help("Ed25519 private key: file path or hex string")

    override fun run() {
        // ======== Phase 1: Compile ========
        echo("========== Phase 1: Compile ==========")

        val wasmtimeExec = Compile.findWasmtimeExecutable(wasmtimeDir)
        if (wasmtimeExec == null) {
            echo("Error: Could not find 'wasmtime' executable in ${wasmtimeDir.absolutePath}", err = true)
            return
        }
        echo("Using Wasmtime: ${wasmtimeExec.absolutePath}")

        val resolvedName = name ?: inputFile.nameWithoutExtension
        val outputDir = File("build/wasmline/output", "$resolvedName-$version")
        if (!outputDir.exists()) outputDir.mkdirs()
        val debugDir = File(outputDir, "debug")
        if (!debugDir.exists()) debugDir.mkdirs()

        val finalTargets = if (targets.isEmpty()) Compile.DEFAULT_TARGETS else targets
        echo("Input File: ${inputFile.name}")
        echo("Product name: $resolvedName")
        echo("Output: ${outputDir.absolutePath}")

        val artifacts = Compile.compileAll(wasmtimeExec, inputFile, outputDir, resolvedName, finalTargets) { echo(it) }
        if (artifacts.isEmpty()) {
            echo("Error: No artifacts compiled successfully. Aborting.", err = true)
            return
        }

        Compile.writeCompileResult(inputFile, debugDir, artifacts)

        // ======== Phase 2: Manifest ========
        echo("========== Phase 2: Manifest ==========")

        val resolvedPluginId = pluginId ?: resolvedName
        val manifest = WasmlineManifest(
            pluginId = resolvedPluginId,
            version = version,
            versionCode = versionCode,
            minSdkVersion = minSdkVersion,
            displayName = displayName,
            author = author,
            description = description,
            iconUrl = iconUrl,
            homePageUrl = homeUrl,
            buildTimestamp = System.currentTimeMillis(),
            artifacts = artifacts
        )

        val privateKeyHex = Manifest.resolveKey(key)
        val privateKey = privateKeyHex.decodeHex()
        val manifestBytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), manifest)
        val signature = Ed25519.sign(manifestBytes.toByteString(), privateKey)
        echo("Manifest signed with Ed25519")

        val envelope = SignedManifestEnvelope(
            signature = signature.toByteArray(),
            manifest = manifest,
            algorithm = "Ed25519"
        )

        val envelopeBytes = ProtoBuf.encodeToByteArray(SignedManifestEnvelope.serializer(), envelope)
        val wlmFile = File(outputDir, Manifest.DEFAULT_MANIFEST_NAME)
        wlmFile.writeBytes(envelopeBytes)
        echo("Manifest written to: ${wlmFile.absolutePath} (${envelopeBytes.size} bytes)")

        val jsonFile = File(debugDir, "manifest.json")
        jsonFile.writeText(baseJson.encodeToString(envelope))
        echo("JSON written to: ${jsonFile.absolutePath}")

        // ======== Phase 3: Package ========
        echo("========== Phase 3: Package ==========")

        val distDir = File("build/wasmline/dist")
        if (!distDir.exists()) distDir.mkdirs()

        val zipName = "$resolvedName-$version.zip"
        val zipFile = File(distDir, zipName)
        val folderPrefix = "$resolvedName-$version"

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            addToZip(zos, wlmFile, "$folderPrefix/${wlmFile.name}")
            artifacts.forEach { artifact ->
                val file = File(outputDir, artifact.url)
                if (file.exists()) {
                    addToZip(zos, file, "$folderPrefix/${file.name}")
                }
            }
        }

        echo("Package written to: ${zipFile.absolutePath} (${zipFile.length()} bytes)")
        echo("==========  Build Complete  ==========")
        echo("Artifacts: ${artifacts.size}")
    }

    private fun addToZip(zos: ZipOutputStream, file: File, entryName: String) {
        zos.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
    }
}
