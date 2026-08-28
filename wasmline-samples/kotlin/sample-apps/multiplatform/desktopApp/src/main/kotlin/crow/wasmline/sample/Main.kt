@file:OptIn(InternalResourceApi::class, ExperimentalSerializationApi::class)

package crow.wasmline.sample

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineManifest
import crow.wasmline.loader.model.WasmlineManifestProtocol
import crow.wasmline.loader.model.WasmlineManifestWireFormat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.light
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.DecoratedWindowScope
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.styling.TitleBarColors
import org.jetbrains.jewel.window.styling.TitleBarStyle
import java.awt.Dimension
import java.io.File
import java.nio.file.Paths
import javax.imageio.ImageIO

private const val coreServicePackageRoot = "wasmline-packages/core-service"
private const val rawExportPackageRoot = "wasmline-packages/raw-export"
private const val componentServicePackageRoot = "wasmline-packages/component-service"
private const val componentExportPackageRoot = "wasmline-packages/component-export"

@OptIn(ExperimentalResourceApi::class)
fun main() {
    if (System.getProperty("wasmline.sample.smoke").toBoolean()) {
        runDesktopSmokeTest()
        return
    }

    application {
        val packages = extractPluginPackagesToTemp().also(::logExtractedPackages)
        val refresher = remember(packages) { DesktopAssetRefresher(packages.values) }
        val artifacts = configuredSampleArtifacts(packages)
        AppWindows {
            App(
                wasmPath = artifacts.coreServicePath,
                assetRefresher = refresher,
                artifacts = artifacts,
            )
        }
    }
}

private fun runDesktopSmokeTest() {
    val packages = extractPluginPackagesToTemp().also(::logExtractedPackages)
    val reports = runBlocking {
        verifySampleArtifacts(
            artifacts = configuredSampleArtifacts(packages),
            assetRefresher = DesktopAssetRefresher(packages.values),
        )
    }
    reports.forEach { report ->
        println(
            "[Sample smoke] ${report.mode.title}: ${report.status}; " +
                "action=${report.executedAction}; output=${report.outputJson}",
        )
    }
    val failures = reports.filter { it.status != WasmExecutionStatus.Success }
    check(failures.isEmpty()) {
        failures.joinToString(prefix = "Sample smoke test failed: ", separator = "; ") { report ->
            "${report.mode.title}: ${report.errorMessage.ifBlank { report.detail }}"
        }
    }
}

private fun logExtractedPackages(packages: Map<WasmSampleMode, BundledPluginPackage>) {
    packages.forEach { (mode, pluginPackage) ->
        println("${mode.title} package extracted to: ${pluginPackage.manifestFile.absolutePath}")
    }
}

private fun configuredSampleArtifacts(packages: Map<WasmSampleMode, BundledPluginPackage>): SampleArtifacts =
    SampleArtifacts(
        coreServicePath = configuredArtifact(
            property = "wasmline.sample.coreService",
            environment = "WASMLINE_SAMPLE_CORE_SERVICE",
            default = packages.getValue(WasmSampleMode.CORE_SERVICE).manifestFile.absolutePath,
        ),
        rawExportPath = configuredArtifact(
            property = "wasmline.sample.rawExport",
            environment = "WASMLINE_SAMPLE_RAW_EXPORT",
            default = packages.getValue(WasmSampleMode.RAW_EXPORT).manifestFile.absolutePath,
        ),
        componentServicePath = configuredArtifact(
            property = "wasmline.sample.componentService",
            environment = "WASMLINE_SAMPLE_COMPONENT_SERVICE",
            default = packages.getValue(WasmSampleMode.COMPONENT_SERVICE).manifestFile.absolutePath,
        ),
        componentFixturePath = configuredArtifact(
            property = "wasmline.sample.componentFixture",
            environment = "WASMLINE_SAMPLE_COMPONENT_FIXTURE",
            default = "",
        ),
        componentExportPath = configuredArtifact(
            property = "wasmline.sample.componentExport",
            environment = "WASMLINE_SAMPLE_COMPONENT_EXPORT",
            default = packages.getValue(WasmSampleMode.COMPONENT_EXPORT).manifestFile.absolutePath,
        ),
    )

private fun configuredArtifact(property: String, environment: String, default: String): String =
    System.getProperty(property)?.takeIf(String::isNotBlank)
        ?: System.getenv(environment)?.takeIf(String::isNotBlank)
        ?: default

@Composable
fun ApplicationScope.AppWindows(content: @Composable () -> Unit) {
    val iconPainter = remember { loadWindowIcon() }
    val windowState = rememberWindowState(size = DpSize(width = 800.dp, height = 860.dp))
    IntUiTheme(
        theme = JewelTheme.lightThemeDefinition(),
        styling = ComponentStyling.default()
            .decoratedWindow(
                titleBarStyle = TitleBarStyle.light(
                    colors = TitleBarColors.lightWithLightHeader(backgroundColor = Color.White.copy(alpha = 0.7f))
                )
            ),
    ) {
        MaterialTheme {
            DecoratedWindow(
                onCloseRequest = ::exitApplication,
                state = windowState,
                title = "Wasmline",
                enabled = true,
                visible = true,
                icon = iconPainter,
            ) {
                window.minimumSize = Dimension(640, 600)
                DesktopTitleBar()
                content()
            }
        }
    }
}

private fun loadWindowIcon(): BitmapPainter {
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("wasmline-icon.png")
        ?: error("Resource not found: wasmline-icon.png")
    return stream.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }
}

@Composable
fun DecoratedWindowScope.DesktopTitleBar(
    title: String = "Wasmline"
) {
    TitleBar {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}


@OptIn(ExperimentalResourceApi::class)
fun copyResourceToFile(resourcePath: String, targetFile: File) {
    targetFile.parentFile?.mkdirs()
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath)
        ?: error("Resource not found: $resourcePath")
    stream.use { input ->
        targetFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }
}

private data class BundledPluginPackage(
    val directory: File,
    val manifestFile: File,
    val resourcePaths: Map<String, String>,
)

@OptIn(ExperimentalSerializationApi::class)
private fun bundledPluginResourcePaths(packageRoot: String): Map<String, String> {
    val classLoader = Thread.currentThread().contextClassLoader
    val manifestResource = "$packageRoot/manifest.wlm"
    val manifestBytes = classLoader.getResourceAsStream(manifestResource)?.use { it.readBytes() }
        ?: error("Resource not found: $manifestResource")
    val envelope = ProtoBuf.decodeFromByteArray(SignedManifestEnvelope.serializer(), manifestBytes)
    require(envelope.formatVersion == WasmlineManifestWireFormat.CURRENT_FORMAT_VERSION) {
        "Unsupported bundled manifest format ${envelope.formatVersion}."
    }
    val manifest = ProtoBuf.decodeFromByteArray(WasmlineManifest.serializer(), envelope.payload)
    val relativePaths = buildList {
        add("manifest.wlm")
        manifest.artifactTargets.forEach { target ->
            target.variants.mapTo(this) { variant ->
                WasmlineManifestProtocol.artifactRelativePath(variant.sha256, target.format)
            }
        }
    }.distinct()

    return relativePaths.associate { relativePath ->
        val safePath = requireSafePackageRelativePath(relativePath)
        val resourcePath = "$packageRoot/$safePath".also { resourcePath ->
            require(classLoader.getResource(resourcePath) != null) {
                "Wasmline package resource not found: $resourcePath"
            }
        }
        safePath to resourcePath
    }
}

private fun requireSafePackageRelativePath(path: String): String {
    val normalized = Paths.get(path).normalize()
    val safePath = normalized.joinToString("/")
    require(
        path.isNotBlank() &&
            safePath.isNotBlank() &&
            safePath != "." &&
            !normalized.isAbsolute &&
            !normalized.startsWith(".."),
    ) {
        "Wasmline package contains an invalid relative artifact path: $path"
    }
    return safePath
}

@OptIn(ExperimentalResourceApi::class)
private fun copyBundledPluginPackage(pluginPackage: BundledPluginPackage) {
    pluginPackage.resourcePaths.forEach { (relativePath, resourcePath) ->
        val targetFile = File(pluginPackage.directory, relativePath)
        copyResourceToFile(resourcePath, targetFile)
        targetFile.deleteOnExit()
    }
}

@OptIn(ExperimentalResourceApi::class)
private fun extractPluginPackageToTemp(mode: WasmSampleMode, packageRoot: String): BundledPluginPackage {
    val packageDirectory = kotlin.io.path.createTempDirectory("wasmline_${mode.name.lowercase()}_").toFile()
    packageDirectory.deleteOnExit()
    val pluginPackage = BundledPluginPackage(
        directory = packageDirectory,
        manifestFile = File(packageDirectory, "manifest.wlm"),
        resourcePaths = bundledPluginResourcePaths(packageRoot),
    )
    copyBundledPluginPackage(pluginPackage)
    return pluginPackage
}

private fun extractPluginPackagesToTemp(): Map<WasmSampleMode, BundledPluginPackage> = mapOf(
    WasmSampleMode.CORE_SERVICE to extractPluginPackageToTemp(WasmSampleMode.CORE_SERVICE, coreServicePackageRoot),
    WasmSampleMode.RAW_EXPORT to extractPluginPackageToTemp(WasmSampleMode.RAW_EXPORT, rawExportPackageRoot),
    WasmSampleMode.COMPONENT_SERVICE to extractPluginPackageToTemp(
        WasmSampleMode.COMPONENT_SERVICE,
        componentServicePackageRoot,
    ),
    WasmSampleMode.COMPONENT_EXPORT to extractPluginPackageToTemp(
        WasmSampleMode.COMPONENT_EXPORT,
        componentExportPackageRoot,
    ),
)

/**
 * Desktop implementation of [AssetRefresher].
 * Re-extracts the complete classpath package over its stable temp directory.
 */
@OptIn(ExperimentalResourceApi::class)
private class DesktopAssetRefresher(
    pluginPackages: Collection<BundledPluginPackage>,
) : AssetRefresher {
    private val packagesByManifest = pluginPackages.associateBy { it.manifestFile.canonicalFile }

    override suspend fun refresh(wasmPath: String): String {
        val requestedFile = File(wasmPath).canonicalFile
        val pluginPackage = packagesByManifest[requestedFile]
        if (pluginPackage == null) {
            println("[DesktopAssetRefresher] Custom artifact is already current: ${requestedFile.path}")
            return requestedFile.path
        }

        copyBundledPluginPackage(pluginPackage)
        println("[DesktopAssetRefresher] Re-extracted package to: ${pluginPackage.directory.path}")
        return pluginPackage.manifestFile.path
    }
}
