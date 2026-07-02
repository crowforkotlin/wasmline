@file:OptIn(InternalResourceApi::class, ExperimentalSerializationApi::class)

package crow.wasmline.sample

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.application
import crow.wasmline.loader.WasmlineLoader
import kotlinx.serialization.ExperimentalSerializationApi
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
        else -> error("Unsupported runtime artifact format '$rawFormat'. Expected pwasm or cwasm.")
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
            "Requested ${requestedFormat} bundled artifact was not found. " +
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

    error("Resource not found: ${bundledPluginResources.joinToString(" or ")}")
}

@OptIn(ExperimentalResourceApi::class)
fun main() = application {
    WasmlineLoader.bootstrap()
    val (resourceName, wasmFile) = extractPluginArtifactToTemp()
    println("Wasm extracted from $resourceName to: ${wasmFile.absolutePath}")
    val refresher = remember(resourceName) { DesktopAssetRefresher(resourceName) }
    AppWindows {
        App(
            wasmPath = wasmFile.absolutePath,
            assetRefresher = refresher,
        )
    }
}

@Composable
fun ApplicationScope.AppWindows(content: @Composable () -> Unit) {
    var iconPainter by remember { mutableStateOf<BitmapPainter?>(null) }
    LaunchedEffect(Unit) {
//        iconPainter = BitmapPainter(image = readResourceBytes("favicon.ico").decodeToImageBitmap())
    }
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
                title = "Wasmline Sample ",
                enabled = true,
                visible = true,
                icon = iconPainter
            ) {
                window.minimumSize = Dimension(352, 320)
                DesktopTitleBar()
                content()
            }
        }
    }
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

@OptIn(ExperimentalResourceApi::class)
fun extractResourceToTemp(resourcePath: String): File {
    val suffix = "." + resourcePath.substringAfterLast('.', missingDelimiterValue = "bin")
    val prefix = "wasmline_plugin_${resourcePath.substringAfterLast('.', missingDelimiterValue = "bin")}_"
    val tempFile = File.createTempFile(prefix, suffix)
    tempFile.deleteOnExit()
    copyResourceToFile(resourcePath, tempFile)
    return tempFile
}

@OptIn(ExperimentalResourceApi::class)
fun extractPluginArtifactToTemp(): Pair<String, File> {
    val resourceNames = resolveBundledPluginResourceNames()
    if (resourceNames.size == 1) {
        val resourceName = resourceNames.single()
        return resourceName to extractResourceToTemp(resourceName)
    }

    val markerFile = File.createTempFile("wasmline_plugin_bundle_", ".tmp")
    val parentDir = markerFile.parentFile
    val baseName = markerFile.name.removeSuffix(".tmp")
    markerFile.delete()

    val extractedFiles = linkedMapOf<String, File>()
    for (resourceName in resourceNames) {
        val suffix = "." + resourceName.substringAfterLast('.', missingDelimiterValue = "bin")
        val targetFile = File(parentDir, baseName + suffix)
        targetFile.deleteOnExit()
        copyResourceToFile(resourceName, targetFile)
        extractedFiles[resourceName] = targetFile
    }

    val primaryResourceName = resourceNames.first()
    return primaryResourceName to checkNotNull(extractedFiles[primaryResourceName])
}

/**
 * Desktop implementation of [AssetRefresher].
 * Deletes the current temp file and re-extracts from classpath resources.
 */
@OptIn(ExperimentalResourceApi::class)
private class DesktopAssetRefresher(
    private val primaryResourceName: String,
) : AssetRefresher {
    override suspend fun refresh(wasmPath: String): String {
        val oldFile = File(wasmPath)
        oldFile.delete()
        val newFile = extractResourceToTemp(primaryResourceName)
        println("[DesktopAssetRefresher] Re-extracted to: ${newFile.absolutePath}")
        return newFile.absolutePath
    }
}
