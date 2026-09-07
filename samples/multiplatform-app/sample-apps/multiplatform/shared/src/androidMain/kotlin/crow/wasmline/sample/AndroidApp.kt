package crow.wasmline.sample

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val BUNDLED_MANIFEST_ASSET = "manifest.wlm"
private const val BUNDLED_ARTIFACTS_ASSET = "artifacts"

@Composable
fun AndroidApp(autoExecute: Boolean = false) {
    val context = LocalContext.current
    val packageDirectory = remember(context) { File(context.cacheDir, "wasmline-package") }
    val manifestFile = remember(packageDirectory) { File(packageDirectory, BUNDLED_MANIFEST_ASSET) }
    val refresher = remember(context, packageDirectory) { AndroidAssetRefresher(context, packageDirectory) }
    var packageReady by remember(manifestFile) { mutableStateOf(manifestFile.isFile) }

    App(
        wasmPath = manifestFile.absolutePath,
        autoExecute = autoExecute && packageReady,
        execDispatcher = Dispatchers.Main,
        assetRefresher = refresher,
    )
    LaunchedEffect(context, packageDirectory) {
        ensureBundledPackageCopied(context, packageDirectory)
        packageReady = true
    }
}

private suspend fun ensureBundledPackageCopied(context: Context, destination: File, forceOverwrite: Boolean = false) {
    val manifest = File(destination, BUNDLED_MANIFEST_ASSET)
    if (manifest.isFile && !forceOverwrite) return
    withContext(Dispatchers.IO) {
        if (forceOverwrite) destination.deleteRecursively()
        copyAssetTree(context, BUNDLED_MANIFEST_ASSET, manifest)
        copyAssetTree(context, BUNDLED_ARTIFACTS_ASSET, File(destination, BUNDLED_ARTIFACTS_ASSET))
    }
}

private fun copyAssetTree(context: Context, assetPath: String, destination: File) {
    val children = context.assets.list(assetPath).orEmpty()
    if (children.isEmpty()) {
        destination.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        return
    }

    check(destination.isDirectory || destination.mkdirs()) {
        "Unable to create Android package directory: ${destination.absolutePath}"
    }
    children.forEach { child ->
        copyAssetTree(context, "$assetPath/$child", File(destination, child))
    }
}

/**
 * Restores the complete signed package from APK assets for explicit reloads.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
private class AndroidAssetRefresher(private val context: Context, private val packageDirectory: File) : AssetRefresher {
    override suspend fun refresh(wasmPath: String): String {
        ensureBundledPackageCopied(context, packageDirectory, forceOverwrite = true)
        return File(packageDirectory, BUNDLED_MANIFEST_ASSET).absolutePath
    }
}
