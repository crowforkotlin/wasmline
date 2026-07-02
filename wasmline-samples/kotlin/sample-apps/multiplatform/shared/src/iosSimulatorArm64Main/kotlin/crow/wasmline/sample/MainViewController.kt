package crow.wasmline.sample

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.ComposeUIViewController
import crow.wasmline.loader.WasmlineLoader
import platform.Foundation.NSBundle

fun MainViewController() = run {
    WasmlineLoader.bootstrap()

    ComposeUIViewController {
        MaterialTheme {
            DisposableEffect(Unit) {
                onDispose {
                    WasmlineLoader.shutdown()
                }
            }

            App(
                wasmPath = NSBundle.mainBundle.pathForResource("plugin", "pwasm") ?: return@MaterialTheme,
                autoExecute = true,
                assetRefresher = NoOpAssetRefresher,
            )
        }
    }
}
