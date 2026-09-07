package crow.wasmline.plugin.core.manifest

import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import crow.wasmline.plugin.core.aot.WasmlineAotBuildRecords
import java.io.File
import java.util.Properties

/**
 * Runs manifest signing in an isolated JVM for build-tool integrations.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
object ManifestSigningMain {
    /** Reads one properties request and writes the signed package metadata. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Expected one manifest signing request file." }
        val request = Properties().apply {
            File(args.single()).inputStream().use(::load)
        }
        ManifestSigner().createSignedManifest(
            WasmlineManifestSigningRequest(
                buildRecord = WasmlineAotBuildRecords.read(File(request.required(AOT_BUILD_RECORD_FILE))),
                pluginId = request.required(PLUGIN_ID),
                version = request.required(PLUGIN_VERSION),
                versionCode = request.required(VERSION_CODE).toLong(),
                minSdkVersion = request.required(MIN_SDK_VERSION),
                buildTimestamp = request.required(BUILD_TIMESTAMP).toLong(),
                signingKey = request.required(SIGNING_KEY),
                outputDirectory = File(request.required(OUTPUT_DIRECTORY)),
                displayName = request.getProperty(DISPLAY_NAME),
                author = request.getProperty(AUTHOR),
                description = request.getProperty(DESCRIPTION),
                iconUrl = request.getProperty(ICON_URL),
                homePageUrl = request.getProperty(HOME_PAGE_URL),
                metadata = request.prefixedMap(METADATA_PREFIX),
                logger = ::println,
            ),
        )
    }

    private fun Properties.required(name: String): String =
        getProperty(name)?.takeIf(String::isNotBlank) ?: error("Missing manifest signing property: $name")

    private fun Properties.prefixedMap(prefix: String): Map<String, String> = stringPropertyNames()
        .asSequence()
        .filter { name -> name.startsWith(prefix) }
        .associate { name -> name.removePrefix(prefix) to getProperty(name) }

    const val AOT_BUILD_RECORD_FILE: String = "aotBuildRecordFile"
    const val OUTPUT_DIRECTORY: String = "outputDirectory"
    const val PLUGIN_ID: String = "pluginId"
    const val PLUGIN_VERSION: String = "pluginVersion"
    const val VERSION_CODE: String = "versionCode"
    const val MIN_SDK_VERSION: String = "minSdkVersion"
    const val BUILD_TIMESTAMP: String = "buildTimestamp"
    const val SIGNING_KEY: String = "signingKey"
    const val DISPLAY_NAME: String = "displayName"
    const val AUTHOR: String = "author"
    const val DESCRIPTION: String = "description"
    const val ICON_URL: String = "iconUrl"
    const val HOME_PAGE_URL: String = "homePageUrl"
    const val METADATA_PREFIX: String = "metadata."
}
