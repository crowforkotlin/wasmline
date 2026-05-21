package crow.wasmline.sample.application

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineLoadState
import crow.wasmline.bind
import crow.wasmline.link
import crow.wasmline.loader.loadWasmline
import crow.wasmline.sample.bean.PlatformBean
import crow.wasmline.sample.extensions.toJsonString
import crow.wasmline.sample.extensions.toProtoBean
import crow.wasmline.sample.extensions.toProtoBytes
import crow.wasmline.sample.ir.EchoService
import crow.wasmline.sample.ir.TimeSyncService
import java.io.File
import java.time.Instant

private val bundledPluginResources = listOf("plugin.generated.pwasm", "plugin.pwasm")

internal fun runApplicationSample() {
    Wasmline.init()
    val resourceName = bundledPluginResources.firstOrNull {
        Thread.currentThread().contextClassLoader.getResource(it) != null
    } ?: error("[Application] Resource not found: plugin.generated.pwasm or plugin.pwasm")
    val artifactFile = extractBundledPluginArtifact(resourceName)
    println("[Application] Loading bundled artifact ($resourceName) from: ${artifactFile.absolutePath}")

    try {
        when (val loadState = loadWasmline(artifactPath = artifactFile.absolutePath, threadSafe = false)) {
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

                val response = module.link<TimeSyncService>().timeSync(toProtoBytes(request))
                val bean = toProtoBean<PlatformBean>(response)
                println("[Application] Plugin response: ${toJsonString(bean)}")
                module.close()
            }
        }
    } finally {
        Wasmline.shutdown()
    }
}

private fun extractBundledPluginArtifact(resourceName: String): File {
    val suffix = "." + resourceName.substringAfterLast('.', missingDelimiterValue = "bin")
    val tempFile = File.createTempFile("wasmline_application_plugin", suffix)
    tempFile.deleteOnExit()
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(resourceName)
        ?: error("[Application] Resource not found: $resourceName")
    stream.use { input ->
        tempFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    return tempFile
}
