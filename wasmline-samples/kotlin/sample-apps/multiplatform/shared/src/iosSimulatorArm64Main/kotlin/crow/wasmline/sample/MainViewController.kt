package crow.wasmline.sample

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSBundle

fun MainViewController() =
    ComposeUIViewController {
        MaterialTheme {
            App(
                wasmPath = NSBundle.mainBundle.pathForResource("manifest", "wlm", "plugin-package")
                    ?: return@MaterialTheme,
                autoExecute = true,
                assetRefresher = NoOpAssetRefresher,
            )
        }
    }
