package crow.wasmline.sample

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private val bundledPluginAssets = listOf("plugin.cwasm", "plugin.pwasm")

@Composable
fun AndroidApp(
    wasmFilename: String? = null,
    autoExecute: Boolean = false,
) {
    val context = LocalContext.current
    val resolvedWasmFilename = resolveAndroidAssetFilename(context, wasmFilename)
    val wasmFile = File(context.cacheDir, resolvedWasmFilename)
    val refresher = remember(context) { AndroidAssetRefresher(context) }
    App(
        wasmPath = wasmFile.absolutePath,
        autoExecute = autoExecute,
        execDispatcher = Dispatchers.Main,
        assetRefresher = refresher,
    )
    LaunchedEffect(context, resolvedWasmFilename) {
        ensureAndroidAssetCopied(
            context = context,
            wasmFilename = resolvedWasmFilename,
            destination = wasmFile,
        )
    }
}

private fun resolveAndroidAssetFilename(context: Context, explicitFilename: String?): String {
    if (explicitFilename != null) {
        return explicitFilename
    }

    val availableAssets = context.assets.list("")?.toSet().orEmpty()
    return bundledPluginAssets.firstOrNull { it in availableAssets }
        ?: error("Android asset not found: ${bundledPluginAssets.joinToString(" or ")}")
}

private suspend fun ensureAndroidAssetCopied(
    context: Context,
    wasmFilename: String,
    destination: File,
    forceOverwrite: Boolean = false,
) {
    if (destination.exists() && !forceOverwrite) {
        return
    }
    withContext(Dispatchers.IO) {
        context.assets.open(wasmFilename).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
    }
}

/**
 * Android implementation of [AssetRefresher].
 * Deletes the cached wasm file and re-copies it from APK assets.
 */
private class AndroidAssetRefresher(
    private val context: Context,
) : AssetRefresher {
    override suspend fun refresh(wasmPath: String): String {
        val file = File(wasmPath)
        val filename = file.name
        withContext(Dispatchers.IO) {
            file.delete()
            context.assets.open(filename).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return wasmPath
    }
}
