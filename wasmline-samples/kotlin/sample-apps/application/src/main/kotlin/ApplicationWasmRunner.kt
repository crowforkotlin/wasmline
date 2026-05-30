package crow.wasmline.sample.application

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineLoadState
import crow.wasmline.bind
import crow.wasmline.link
import crow.wasmline.loader.loadWasmline
import crow.wasmline.serialization.WasmlineSerializationConfig
import crow.wasmline.sample.bean.PlatformBean
import crow.wasmline.sample.extensions.toJsonString
import crow.wasmline.sample.ir.EchoService
import crow.wasmline.sample.ir.TimeSyncService
import java.io.File
import java.time.Instant

private const val artifactFormatProperty = "wasmline.artifact.format"
private const val artifactFormatEnvironment = "WASMLINE_ARTIFACT_FORMAT"

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
        "pwasm", "cwasm" -> normalized
        else -> error("[Application] Unsupported runtime artifact format '$rawFormat'. Expected pwasm or cwasm.")
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
            "pwasm" -> findBundledPluginResource("plugin.pwasm", "plugin.generated.pwasm")
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

internal fun runApplicationSample() {
    Wasmline.bootstrap()
    val (resourceName, artifactFile) = extractBundledPluginArtifact()
    println("[Application] Loading bundled artifact ($resourceName) from: ${artifactFile.absolutePath}")

    try {
        when (
            val loadState = loadWasmline(
                artifactPath = artifactFile.absolutePath,
                threadSafe = false,
                config = WasmlineConfig(serialization = WasmlineSerializationConfig.protobuf()),
            )
        ) {
            is WasmlineLoadState.Failure -> {
                error("[Application] Failed to load wasm: ${loadState.cause}")
            }

            is WasmlineLoadState.Success -> {
                println("[Application] Wasm load success: ${loadState.code}")
                val module = loadState.wasmline
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
        Wasmline.shutdown()
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
