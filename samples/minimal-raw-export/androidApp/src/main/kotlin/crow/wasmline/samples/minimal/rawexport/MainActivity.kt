package crow.wasmline.samples.minimal.rawexport

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import crow.wasmline.samples.minimal.library.platform.androidEnvironment

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val environment = remember { androidEnvironment(applicationContext) }
            App(environment)
        }
    }
}
