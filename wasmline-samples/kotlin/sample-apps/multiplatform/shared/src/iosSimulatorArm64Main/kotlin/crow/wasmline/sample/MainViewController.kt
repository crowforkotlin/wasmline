package crow.wasmline.sample

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.ComposeUIViewController
import crow.wasmline.Wasmline
import platform.Foundation.NSBundle

fun MainViewController() = run {
    Wasmline.bootstrap()

    ComposeUIViewController {
        MaterialTheme {
            DisposableEffect(Unit) {
                onDispose {
                    Wasmline.shutdown()
                }
            }

            App(
                wasmPath = NSBundle.mainBundle.pathForResource("plugin", "pwasm") ?: return@MaterialTheme,
                autoExecute = true,
            )
        }
    }
}
