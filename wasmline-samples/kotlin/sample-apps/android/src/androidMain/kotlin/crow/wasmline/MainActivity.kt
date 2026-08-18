package crow.wasmline

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import crow.wasmline.extensions.info
import crow.wasmline.sample.AndroidApp
import kotlin.system.measureTimeMillis

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        "[Android] Preload Wasmline took ${measureTimeMillis { WasmlineRuntime.preload() }} ms".info()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.navigationBarColor = Color.TRANSPARENT
        }
        setContent {
            MaterialTheme {
                AndroidApp()
            }
        }
    }
}
