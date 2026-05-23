@file:Suppress("SpellCheckingInspection")

package crow.wasmline.sample

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

class MainActivity : BaseActivity() {
    @Composable
    override fun composeContent() {
        MaterialTheme { AndroidApp() }
    }
}
