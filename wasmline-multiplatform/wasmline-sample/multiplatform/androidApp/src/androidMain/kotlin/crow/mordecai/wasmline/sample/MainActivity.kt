@file:SuppressLint("SetTextI18n")
@file:OptIn(ExperimentalSerializationApi::class)
@file:Suppress("SpellCheckingInspection")

package crow.mordecai.wasmline.sample

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File
import java.io.FileOutputStream

class MainActivity : BaseActivity() {
    @Composable
    override fun composeContent() {
        MaterialTheme { AndroidApp() }
    }
}



@Composable
fun AndroidApp() {
    val context = LocalContext.current
    Box(modifier = Modifier.statusBarsPadding()) {
        App(
            wasmPath = File(context.cacheDir, "plugin.pwasm").absolutePath,
        )
    }

    LaunchedEffect(Unit) {
        val wasmFilename = "plugin.pwasm"
        val wasmFile = File(context.cacheDir, wasmFilename)
        val cwasmFile = File(context.cacheDir, "plugin.pwasm")
        if (!wasmFile.exists()) {
            withContext(Dispatchers.IO) {
                context.assets.open(wasmFilename).use { input ->
                    FileOutputStream(wasmFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}