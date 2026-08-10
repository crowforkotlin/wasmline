package crow.wasmline.plugin.core.manifest

import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import java.io.File
import java.util.Properties

/** Isolated JVM entrypoint used by build-tool integrations that share dependency classloaders. */
object ManifestSigningMain {

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Expected one manifest signing request file." }
        val request = Properties().apply {
            File(args.single()).inputStream().use(::load)
        }
        val outputDirectory = File(request.required(OUTPUT_DIRECTORY))
        val compileResult = WasmtimeCompiler().readCompileResult(File(request.required(COMPILE_RESULT_FILE)))

        ManifestSigner().createSignedManifest(
            artifacts = compileResult.artifacts,
            pluginId = request.required(PLUGIN_ID),
            version = request.required(PLUGIN_VERSION),
            versionCode = request.required(VERSION_CODE).toLong(),
            minSdkVersion = request.required(MIN_SDK_VERSION),
            signingKey = request.required(SIGNING_KEY),
            outputDir = outputDirectory,
            displayName = request.getProperty(DISPLAY_NAME),
            author = request.getProperty(AUTHOR),
            description = request.getProperty(DESCRIPTION),
            iconUrl = request.getProperty(ICON_URL),
            homePageUrl = request.getProperty(HOME_PAGE_URL),
            metadata = request.prefixedMap(METADATA_PREFIX),
            executionModel = WasmlineExecutionModel.valueOf(request.required(EXECUTION_MODEL)),
            invocationProtocol = WasmlineInvocationProtocol.valueOf(request.required(INVOCATION_PROTOCOL)),
            exportName = request.getProperty(EXPORT_NAME),
            contractMetadata = request.prefixedMap(CONTRACT_METADATA_PREFIX),
            logger = ::println,
        )
    }

    private fun Properties.required(name: String): String =
        getProperty(name)?.takeIf(String::isNotBlank) ?: error("Missing manifest signing property: $name")

    private fun Properties.prefixedMap(prefix: String): Map<String, String> = stringPropertyNames()
        .asSequence()
        .filter { name -> name.startsWith(prefix) }
        .associate { name -> name.removePrefix(prefix) to getProperty(name) }

    const val COMPILE_RESULT_FILE = "compileResultFile"
    const val OUTPUT_DIRECTORY = "outputDirectory"
    const val PLUGIN_ID = "pluginId"
    const val PLUGIN_VERSION = "pluginVersion"
    const val VERSION_CODE = "versionCode"
    const val MIN_SDK_VERSION = "minSdkVersion"
    const val SIGNING_KEY = "signingKey"
    const val DISPLAY_NAME = "displayName"
    const val AUTHOR = "author"
    const val DESCRIPTION = "description"
    const val ICON_URL = "iconUrl"
    const val HOME_PAGE_URL = "homePageUrl"
    const val EXECUTION_MODEL = "executionModel"
    const val INVOCATION_PROTOCOL = "invocationProtocol"
    const val EXPORT_NAME = "exportName"
    const val METADATA_PREFIX = "metadata."
    const val CONTRACT_METADATA_PREFIX = "contractMetadata."
}
