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
import crow.wasmline.loader.WasmlineLoader
import crow.wasmline.loader.model.SignedManifestEnvelope
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

private const val rawArtifactProperty = "wasmline.sample.raw"
private const val rawArtifactEnvironment = "WASMLINE_SAMPLE_RAW"
private const val componentArtifactProperty = "wasmline.sample.component"
private const val componentArtifactEnvironment = "WASMLINE_SAMPLE_COMPONENT"
private const val bundledPluginPackageRoot = "wasmline-package"
private const val bundledPluginManifest = "$bundledPluginPackageRoot/manifest.wlm"

private fun configuredArtifact(property: String, environment: String): String =
    System.getProperty(property)?.ifBlank { "" }
        ?: System.getenv(environment)?.ifBlank { "" }
        ?: ""

@OptIn(ExperimentalResourceApi::class)
fun main() = application {
    WasmlineLoader.bootstrap()
    val pluginPackage = extractPluginPackageToTemp()
    println("Wasmline package extracted to: ${pluginPackage.manifestFile.absolutePath}")
    val refresher = remember(pluginPackage) { DesktopAssetRefresher(pluginPackage) }
    AppWindows {
        App(
            wasmPath = pluginPackage.manifestFile.absolutePath,
            assetRefresher = refresher,
            artifacts = SampleArtifacts(
                corePath = pluginPackage.manifestFile.absolutePath,
                rawExportPath = configuredArtifact(rawArtifactProperty, rawArtifactEnvironment),
                componentPath = configuredArtifact(componentArtifactProperty, componentArtifactEnvironment),
            ),
        )
    }
}

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
private fun bundledPluginResourcePaths(): Map<String, String> {
    val classLoader = Thread.currentThread().contextClassLoader
    val manifestBytes = classLoader.getResourceAsStream(bundledPluginManifest)?.use { it.readBytes() }
        ?: error("Resource not found: $bundledPluginManifest")
    val envelope = ProtoBuf.decodeFromByteArray(SignedManifestEnvelope.serializer(), manifestBytes)
    val relativePaths = buildList {
        add("manifest.wlm")
        envelope.manifest.artifacts.mapTo(this) { it.url }
    }.distinct()

    return relativePaths.associate { relativePath ->
        val safePath = requireSafePackageRelativePath(relativePath)
        val resourcePath = "$bundledPluginPackageRoot/$safePath".also { resourcePath ->
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
private fun extractPluginPackageToTemp(): BundledPluginPackage {
    val packageDirectory = kotlin.io.path.createTempDirectory("wasmline_plugin_package_").toFile()
    packageDirectory.deleteOnExit()
    val pluginPackage = BundledPluginPackage(
        directory = packageDirectory,
        manifestFile = File(packageDirectory, "manifest.wlm"),
        resourcePaths = bundledPluginResourcePaths(),
    )
    copyBundledPluginPackage(pluginPackage)
    return pluginPackage
}

/**
 * Desktop implementation of [AssetRefresher].
 * Re-extracts the complete classpath package over its stable temp directory.
 */
@OptIn(ExperimentalResourceApi::class)
private class DesktopAssetRefresher(
    private val pluginPackage: BundledPluginPackage,
) : AssetRefresher {
    override suspend fun refresh(wasmPath: String): String {
        val requestedFile = File(wasmPath).canonicalFile
        if (requestedFile != pluginPackage.manifestFile.canonicalFile) {
            println("[DesktopAssetRefresher] Custom artifact is already current: ${requestedFile.path}")
            return requestedFile.path
        }

        copyBundledPluginPackage(pluginPackage)
        println("[DesktopAssetRefresher] Re-extracted package to: ${pluginPackage.directory.path}")
        return pluginPackage.manifestFile.path
    }
}
