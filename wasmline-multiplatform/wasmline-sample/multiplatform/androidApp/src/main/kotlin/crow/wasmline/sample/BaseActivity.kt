package crow.wasmline.sample

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable

abstract class BaseActivity : ComponentActivity() {

    private inline fun immersiveView(onCreate: () -> Unit) {
        enableEdgeToEdge()
        onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.navigationBarColor = Color.TRANSPARENT
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        immersiveView { super.onCreate(savedInstanceState) }
        setContent { composeContent() }
    }

    @Composable
    abstract fun composeContent()
}