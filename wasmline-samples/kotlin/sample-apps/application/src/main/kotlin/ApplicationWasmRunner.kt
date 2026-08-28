@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package crow.wasmline.sample.application

import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineLoadResult
import crow.wasmline.WasmlineRuntime
import crow.wasmline.bind
import crow.wasmline.link
import crow.wasmline.loader.WasmlineLoadOptions
import crow.wasmline.loader.WasmlineLoader
import crow.wasmline.loader.WasmlineTrustedKeySet
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineManifest
import crow.wasmline.loader.model.WasmlineManifestProtocol
import crow.wasmline.loader.model.WasmlineManifestWireFormat
import crow.wasmline.network.ktor.KtorNetworkClient
import crow.wasmline.sample.bean.PlatformBean
import crow.wasmline.sample.extensions.toJsonString
import crow.wasmline.sample.ir.EchoService
import crow.wasmline.sample.ir.TimeSyncService
import crow.wasmline.serialization.WasmlineSerializationConfig
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import kotlinx.serialization.protobuf.ProtoBuf

private const val manifestUrlProperty = "wasmline.manifest.url"
private const val manifestUrlEnvironment = "WASMLINE_MANIFEST_URL"
private const val bundledPackageRoot = "wasmline-package"

private val sampleTrustedKeys = WasmlineTrustedKeySet.Builder()
    .addHex(
        algorithm = "Ed25519",
        keyId = null,
        publicKeyHex = "5a778289bee0c57b05a1c48c8ef312da6ce8e4e4f13fc1a2e8e5aa4cde7ae0db",
    )
    .build()

internal suspend fun runApplicationSample() {
    val source = resolveRemoteManifestUrl() ?: extractBundledPackage().absolutePath
    println("[Application] Loading Wasmline package: $source")
    try {
        val result = WasmlineLoader.load(
            source = source,
            options = WasmlineLoadOptions(
                runtimeConfig = WasmlineConfig(
                    serialization = WasmlineSerializationConfig.protobuf(),
                ),
                networkClient = KtorNetworkClient(),
                trustedKeys = sampleTrustedKeys,
            ),
        )
        when (result) {
            is WasmlineLoadResult.Failure -> error("[Application] Failed to load Wasmline package: ${result.failure.message}")
            is WasmlineLoadResult.Success -> {
                val module = result.wasmline
                try {
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
                    println("[Application] Plugin response: ${toJsonString(response)}")
                } finally {
                    module.close()
                }
            }
        }
    } finally {
        WasmlineRuntime.shutdown()
    }
}

private fun resolveRemoteManifestUrl(): String? =
    System.getProperty(manifestUrlProperty)?.takeIf(String::isNotBlank)
        ?: System.getenv(manifestUrlEnvironment)?.takeIf(String::isNotBlank)

private fun extractBundledPackage(): File {
    val classLoader = Thread.currentThread().contextClassLoader
    val manifestResource = "$bundledPackageRoot/manifest.wlm"
    val manifestBytes = classLoader.getResourceAsStream(manifestResource)?.use { it.readBytes() }
        ?: error("[Application] Resource not found: $manifestResource")
    val envelope = ProtoBuf.decodeFromByteArray(SignedManifestEnvelope.serializer(), manifestBytes)
    require(envelope.formatVersion == WasmlineManifestWireFormat.CURRENT_FORMAT_VERSION) {
        "[Application] Unsupported bundled manifest format ${envelope.formatVersion}."
    }
    val manifest = ProtoBuf.decodeFromByteArray(WasmlineManifest.serializer(), envelope.payload)
    val packageDirectory = kotlin.io.path.createTempDirectory("wasmline_application_package_").toFile()
    val paths = buildList {
        add("manifest.wlm")
        manifest.artifactTargets.forEach { target ->
            target.variants.mapTo(this) { variant ->
                WasmlineManifestProtocol.artifactRelativePath(variant.sha256, target.format)
            }
        }
    }.distinct()
    paths.forEach { relativePath ->
        val safePath = requireSafeRelativePath(relativePath)
        val target = File(packageDirectory, safePath)
        target.parentFile?.mkdirs()
        val resource = "$bundledPackageRoot/$safePath"
        classLoader.getResourceAsStream(resource)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("[Application] Resource not found: $resource")
        target.deleteOnExit()
    }
    packageDirectory.deleteOnExit()
    return File(packageDirectory, "manifest.wlm")
}

private fun requireSafeRelativePath(value: String): String {
    val normalized: Path = Paths.get(value).normalize()
    val safePath = normalized.toString().replace(File.separatorChar, '/')
    require(
        value.isNotBlank() &&
            !normalized.isAbsolute &&
            !normalized.startsWith("..") &&
            safePath != "." &&
            safePath == value,
    ) { "[Application] Invalid package path: $value" }
    return safePath
}
