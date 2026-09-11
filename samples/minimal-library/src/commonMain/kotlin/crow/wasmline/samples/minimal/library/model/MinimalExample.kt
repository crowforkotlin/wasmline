package crow.wasmline.samples.minimal.library.model

import androidx.compose.ui.graphics.Color
import crow.wasmline.Wasmline

class MinimalExample(val initialText: String, val accent: Color, val invocation: (Wasmline) -> String)
