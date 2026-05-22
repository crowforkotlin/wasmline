@file:SuppressLint("SetTextI18n")
@file:OptIn(ExperimentalSerializationApi::class)
@file:Suppress("SpellCheckingInspection")

package crow.wasmline.sample

import android.annotation.SuppressLint
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import crow.wasmline.sample.App
import crow.wasmline.Wasmline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File
import java.io.FileOutputStream
import kotlin.collections.get
import kotlin.time.measureTimedValue

class MainActivity : BaseActivity() {
    @Composable
    override fun composeContent() {
        MaterialTheme { AndroidApp() }
    }
}


@Composable
fun AndroidApp() {
    val context = LocalContext.current
    App(wasmPath = File(context.cacheDir, "plugin.pwasm").absolutePath)
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