package crow.wasmline.sample

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    App(
        wasmPath = wasmFile.absolutePath,
        autoExecute = autoExecute,
        execDispatcher = Dispatchers.Main
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
) {
    if (destination.exists()) {
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
