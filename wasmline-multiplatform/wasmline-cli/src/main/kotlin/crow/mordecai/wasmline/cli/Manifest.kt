@file:Suppress("SpellCheckingInspection", "INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(ExperimentalSerializationApi::class)

package crow.mordecai.wasmline.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.long
import crow.mordecai.wasmline.cli.models.CompileResult
import crow.mordecai.wasmline.loader.internal.crypto.Ed25519
import crow.mordecai.wasmline.cli.BuildConfig
import crow.mordecai.wasmline.model.SignedManifestEnvelope
import crow.mordecai.wasmline.model.WasmlineManifest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import java.io.File

/**
 * Manifest command — reads compile-result.json and metadata options,
 * produces a signed manifest.wlm (Protobuf) and optionally manifest.json (debug).
 *
 * Expects the directory layout produced by [Compile]:
 * ```
 * {dir}/
 *   ├── *.cwasm / *.pwasm
 *   └── debug/
 *       └── compile-result.json
 * ```
 *
 * 2026/2/12
 * @author crowforkotlin
 * @formatter:on
 */
class Manifest : CliktCommand(name = "manifest") {

    // -d --dir: The directory where the compile product is located (such as build/wasmline/output/plugin-1.0.0)
    private val dir by option("-d", "--dir")
        .file(mustExist = true, canBeFile = false, canBeDir = true)
        .required()
        .help("Directory containing compiled artifacts and debug/compile-result.json")

    // --plugin-id
    private val pluginId by option("--plugin-id")
        .help("Plugin unique identifier (e.g., com.mordecai.demo)")

    // --version
    private val version by option("--version")
        .default("1.0.0")
        .help("Semantic version. Default: 1.0.0")

    // --version-code
    private val versionCode by option("--version-code")
        .long()
        .default(1L)
        .help("Integer version code. Default: 1")

    // --min-sdk
    private val minSdkVersion by option("--min-sdk")
        .default(BuildConfig.VERSION)
        .help("Minimum wasmline SDK version. Default: current CLI version")

    // --display-name
    private val displayName by option("--display-name")
        .help("Plugin display name")

    // --author
    private val author by option("--author")
        .help("Plugin author")

    // --description
    private val description by option("--description")
        .help("Plugin description")

    // --icon-url
    private val iconUrl by option("--icon-url")
        .help("Icon URL or relative path")

    // --home-url
    private val homeUrl by option("--home-url")
        .help("Home page or repository URL")

    // --key: Ed25519 private key, supports file path or direct hex string
    private val key by option("-k", "--key")
        .required()
        .help("Ed25519 private key: file path or hex string")

    override fun run() {
        // 1. Read compile-result.json
        val debugDir = File(dir, "debug")
        val resultFile = File(debugDir, Compile.COMPILE_RESULT_FILE)
        if (!resultFile.exists()) {
            echo("Error: ${Compile.COMPILE_RESULT_FILE} not found in ${debugDir.absolutePath}", err = true)
            echo("Run 'wasmline compile' first.", err = true)
            return
        }

        val compileResult: CompileResult = Json.decodeFromString(resultFile.readText())
        echo("Loaded compile result: ${compileResult.artifacts.size} artifacts from ${compileResult.inputFile}")

        // 2. Build WasmlineManifest
        val resolvedPluginId = pluginId ?: compileResult.inputFile.removeSuffix(".wasm")
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
            artifacts = compileResult.artifacts
        )

        // 3. Signature
        val privateKeyHex = resolveKey(key)
        val privateKey = privateKeyHex.decodeHex()
        val manifestBytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), manifest)
        val signature = Ed25519.sign(manifestBytes.toByteString(), privateKey)
        echo("Manifest signed with Ed25519")

        val envelope = SignedManifestEnvelope(
            signature = signature.toByteArray(),
            manifest = manifest,
            algorithm = "Ed25519"
        )

        // 4. Export manifest.wlm to the product directory (same level as .cwasm/.pwasm)
        val envelopeBytes = ProtoBuf.encodeToByteArray(SignedManifestEnvelope.serializer(), envelope)
        val wlmFile = File(dir, DEFAULT_MANIFEST_NAME)
        wlmFile.writeBytes(envelopeBytes)
        echo("--------------------------------------------------")
        echo("Manifest written to: ${wlmFile.absolutePath} (${envelopeBytes.size} bytes)")

        // 5. Debug mode outputs manifest.json to the debug/ directory
        if (!debugDir.exists()) debugDir.mkdirs()
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
        }
        val jsonFile = File(debugDir, "manifest.json")
        jsonFile.writeText(json.encodeToString(envelope))
        echo("Debug JSON written to: ${jsonFile.absolutePath}")
    }

    companion object {
        const val DEFAULT_MANIFEST_NAME = "manifest.wlm"

        /**
         * Parse the key parameter: if it is an existing file path, read the file content, otherwise treat it as a hex string
         *
         * 2026-02-12 02:49:31
         * @author crowforkotlin
         */
        fun resolveKey(key: String): String {
            val file = File(key)
            return if (file.isFile) file.readText().trim() else key.trim()
        }
    }
}
