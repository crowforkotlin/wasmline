package crow.wasmline.sample.application

import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineLoadResult
import crow.wasmline.bind
import crow.wasmline.link
import crow.wasmline.loader.WasmlineLoader
import crow.wasmline.loader.WasmlineLoadOptions
import crow.wasmline.serialization.WasmlineSerializationConfig
import crow.wasmline.network.ktor.KtorNetworkClient
import crow.wasmline.wasmlineNativeRuntimeInfo
import crow.wasmline.sample.bean.PlatformBean
import crow.wasmline.sample.extensions.toJsonString
import crow.wasmline.sample.ir.EchoService
import crow.wasmline.sample.ir.TimeSyncService
import java.io.File
import java.time.Instant

private const val artifactFormatProperty = "wasmline.artifact.format"
private const val artifactFormatEnvironment = "WASMLINE_ARTIFACT_FORMAT"
private const val artifactUrlProperty = "wasmline.artifact.url"
private const val artifactUrlEnvironment = "WASMLINE_ARTIFACT_URL"

private val bundledPluginResources = listOf(
    "plugin.cwasm",
    "plugin.pwasm",
    "plugin.generated.cwasm",
    "plugin.generated.pwasm",
)

private fun requestedBundledArtifactFormat(): String? {
    val rawFormat = System.getProperty(artifactFormatProperty)?.ifBlank { null }
        ?: System.getenv(artifactFormatEnvironment)?.ifBlank { null }
        ?: return null
    val normalized = rawFormat.lowercase()
    return when (normalized) {
        "pwasm", "pwasm32", "pwasm64", "cwasm" -> normalized
        else -> error("[Application] Unsupported runtime artifact format '$rawFormat'. Expected pwasm32, pwasm64, or cwasm.")
    }
}

private fun findBundledPluginResource(vararg candidates: String): String? {
    val classLoader = Thread.currentThread().contextClassLoader
    return candidates.firstOrNull { classLoader.getResource(it) != null }
}

private fun resolveBundledPluginResourceNames(): List<String> {
    val classLoader = Thread.currentThread().contextClassLoader
    val requestedFormat = requestedBundledArtifactFormat()
    if (requestedFormat != null) {
        val selected = when (requestedFormat) {
            "pwasm", "pwasm32", "pwasm64" -> findBundledPluginResource("plugin.pwasm", "plugin.generated.pwasm")
            "cwasm" -> findBundledPluginResource("plugin.cwasm", "plugin.generated.cwasm")
            else -> null
        }
        if (selected != null) {
            return listOf(selected)
        }

        val available = bundledPluginResources.filter { classLoader.getResource(it) != null }
        error(
            "[Application] Requested ${requestedFormat} bundled artifact was not found. " +
                "Available resources: ${available.ifEmpty { listOf("<none>") }.joinToString(", ")}"
        )
    }

    val preferredResources = listOfNotNull(
        findBundledPluginResource("plugin.cwasm", "plugin.generated.cwasm"),
        findBundledPluginResource("plugin.pwasm", "plugin.generated.pwasm"),
    )
    if (preferredResources.isNotEmpty()) {
        return preferredResources
    }

    error("[Application] Resource not found: ${bundledPluginResources.joinToString(" or ")}")
}

private fun resolveRemoteWlmUrl(): String? {
    return System.getProperty(artifactUrlProperty)?.ifBlank { null }
        ?: System.getenv(artifactUrlEnvironment)?.ifBlank { null }
}

private suspend fun loadDirectArtifact(path: String, options: WasmlineLoadOptions): WasmlineLoadResult {
    val format = when {
        path.endsWith(".cwasm", ignoreCase = true) -> WasmlineArtifactFormat.CWASM
        path.endsWith(".pwasm", ignoreCase = true) -> WasmlineArtifactFormat.PWASM
        path.endsWith(".wasm", ignoreCase = true) -> WasmlineArtifactFormat.RAW_WASM
        else -> return WasmlineLoader.load(source = path, options = options)
    }
    val runtime = wasmlineNativeRuntimeInfo()
    val requestedFormat = requestedBundledArtifactFormat()
    val targetCpu = when (format) {
        WasmlineArtifactFormat.CWASM -> runtime?.targetCpu
        WasmlineArtifactFormat.PWASM -> when (requestedFormat) {
            "pwasm32" -> "pulley32"
            "pwasm64" -> "pulley64"
            else -> if (runtime?.is64Bit == false) "pulley32" else "pulley64"
        }
        WasmlineArtifactFormat.RAW_WASM -> null
    }
    val targetOs = when (format) {
        WasmlineArtifactFormat.CWASM -> runtime?.targetOs
        WasmlineArtifactFormat.PWASM, WasmlineArtifactFormat.RAW_WASM -> null
    }
    return WasmlineLoader.load(
        descriptor = WasmlineArtifactDescriptor(
            path = path,
            artifactFormat = format,
            targetCpu = targetCpu,
            targetOs = targetOs,
            targetCompilerVersion = runtime?.wasmtimeVersion?.let { "wasmtime-$it" },
            is64Bit = when (format) {
                WasmlineArtifactFormat.PWASM -> when (requestedFormat) {
                    "pwasm32" -> false
                    "pwasm64" -> true
                    else -> runtime?.is64Bit
                }
                WasmlineArtifactFormat.CWASM, WasmlineArtifactFormat.RAW_WASM -> runtime?.is64Bit
            },
            executionModel = WasmlineExecutionModel.CORE_WASM,
            invocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
        ),
        options = options,
    )
}

internal suspend fun runApplicationSample() {
    WasmlineLoader.bootstrap()

    val remoteUrl = resolveRemoteWlmUrl()
    val source: String
    if (remoteUrl != null) {
        println("[Application] Loading from remote WLM URL: $remoteUrl")
        source = remoteUrl
    } else {
        val (resourceName, artifactFile) = extractBundledPluginArtifact()
        println("[Application] Loading bundled artifact ($resourceName) from: ${artifactFile.absolutePath}")
        source = artifactFile.absolutePath
    }

    try {
        val options = WasmlineLoadOptions(
            runtimeConfig = WasmlineConfig(
                serialization = WasmlineSerializationConfig.protobuf(),
            ),
            networkClient = KtorNetworkClient(),
        )
        when (
            val result = loadDirectArtifact(source, options)
        ) {
            is WasmlineLoadResult.Failure -> {
                error("[Application] Failed to load wasm: ${result.cause}")
            }

            is WasmlineLoadResult.Success -> {
                println("[Application] Wasm load success")
                val module = result.wasmline
                module.bind(object : EchoService {
                    override fun echo() {
                        println("[Application] Plugin invoked host echo()")
                    }
                })

                val request = PlatformBean(
                    platform = "Application",
                    content = "Hello from application",
                    timeStr = Instant.now().toString(),
                    timeMs = System.currentTimeMillis(),
                )
                println("[Application] Sending request: ${toJsonString(request)}")

                val response = module.link<TimeSyncService>().timeSync(request)
                println("[Application] Plugin response: ${toJsonString(value = response)}")
                module.close()
            }
        }
    } finally {
        WasmlineLoader.shutdown()
    }
}

private fun copyBundledPluginArtifact(resourceName: String, targetFile: File) {
    targetFile.parentFile?.mkdirs()
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(resourceName)
        ?: error("[Application] Resource not found: $resourceName")
    stream.use { input ->
        targetFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }
}

private fun extractBundledPluginArtifact(resourceName: String): File {
    val suffix = "." + resourceName.substringAfterLast('.', missingDelimiterValue = "bin")
    val prefix = "wasmline_application_plugin_${resourceName.substringAfterLast('.', missingDelimiterValue = "bin")}_"
    val tempFile = File.createTempFile(prefix, suffix)
    tempFile.deleteOnExit()
    copyBundledPluginArtifact(resourceName, tempFile)
    return tempFile
}

private fun extractBundledPluginArtifact(): Pair<String, File> {
    val resourceNames = resolveBundledPluginResourceNames()
    if (resourceNames.size == 1) {
        val resourceName = resourceNames.single()
        return resourceName to extractBundledPluginArtifact(resourceName)
    }

    val markerFile = File.createTempFile("wasmline_application_plugin_bundle_", ".tmp")
    val parentDir = markerFile.parentFile
    val baseName = markerFile.name.removeSuffix(".tmp")
    markerFile.delete()

    val extractedFiles = linkedMapOf<String, File>()
    for (resourceName in resourceNames) {
        val suffix = "." + resourceName.substringAfterLast('.', missingDelimiterValue = "bin")
        val targetFile = File(parentDir, baseName + suffix)
        targetFile.deleteOnExit()
        copyBundledPluginArtifact(resourceName, targetFile)
        extractedFiles[resourceName] = targetFile
    }

    val primaryResourceName = resourceNames.first()
    return primaryResourceName to checkNotNull(extractedFiles[primaryResourceName])
}
