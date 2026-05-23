package crow.wasmline.sample

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun AndroidApp(
    wasmFilename: String = "plugin.pwasm",
    autoExecute: Boolean = false,
) {
    val context = LocalContext.current
    val wasmFile = File(context.cacheDir, wasmFilename)
    App(
        wasmPath = wasmFile.absolutePath,
        autoExecute = autoExecute,
    )
    LaunchedEffect(context, wasmFilename) {
        ensureAndroidAssetCopied(
            context = context,
            wasmFilename = wasmFilename,
            destination = wasmFile,
        )
    }
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
