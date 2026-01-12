@file:SuppressLint("SetTextI18n")
@file:OptIn(ExperimentalSerializationApi::class)

package crow.mordecai.wasmline

import App
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import crow.mordecai.wasmline.extensions.info
import crow.mordecai.wasmline.extensions.toJsonString
import crow.wasmline.sample.bean.PlatformBean
import crow.wasmline.sample.extensions.toProtoBean
import crow.wasmline.sample.extensions.toProtoBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : BaseActivity() {
    @Composable
    override fun composeContent() {
        MaterialTheme { AndroidApp() }
    }
}


suspend fun loadWasm(context: Context): Wasmline? {
    val wasmFilename = "plugin.32.cwasm"
    val wasmFile = File(context.cacheDir, wasmFilename)
    val cwasmFile = File(context.cacheDir, "plugin.32.cwasm")
    if (!wasmFile.exists()) {
        withContext(Dispatchers.IO) {
            context.assets.open(wasmFilename).use { input ->
                FileOutputStream(wasmFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
    Wasmline.load(filepath = wasmFile.absolutePath, cacheFilepath = cwasmFile.absolutePath, threadSafe = false)
        .onSuccess {
            "[Android] Success : ${this.code}".info()
            return wasmline
        }
        .onFailure {
            "[Android] Failure : ${this.cause}".info()
            return null
        }


    return null
}
@Composable
fun AndroidApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var wasmline: Wasmline? by remember { mutableStateOf(null) }
    var value by remember { mutableStateOf("Value is empty.") }
    Box(modifier = Modifier.statusBarsPadding()) {
        App(value = value) {
            val wasmlineCall = {
                wasmline?.also { wl ->
                    scope.launch {
                        val start = System.currentTimeMillis()
                        val callResult = wl.call(action = "timeSync", inputBytes = toProtoBytes(value = PlatformBean(
                            platform = "Android",
                            content = "${Build.VERSION.SDK_INT}",
                            timeStr = SimpleDateFormat( "yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date(System.currentTimeMillis())),
                            timeMs = System.currentTimeMillis()
                        )))
                        "spend time --> ${System.currentTimeMillis() - start} ms".info()
                        val bean = toProtoBean<PlatformBean>(bytes = callResult)
                        value = toJsonString(value = bean)
                    }
                }
            }
            if (wasmline == null) {
                Toast.makeText(context, "wasmline is null, load failure, retry again", Toast.LENGTH_SHORT).show()
                scope.launch {
                    wasmline = loadWasm(context)
                    wasmlineCall.invoke()
                }
            } else {
                wasmlineCall.invoke()
            }
        }
    }
}