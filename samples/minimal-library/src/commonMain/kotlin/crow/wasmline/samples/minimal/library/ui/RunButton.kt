package crow.wasmline.samples.minimal.library.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RunButton(running: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(160))
    Button(
        onClick = onClick,
        enabled = !running,
        interactionSource = interaction,
        shape = RoundedCornerShape(8.dp),
        elevation = ButtonDefaults.elevation(defaultElevation = 2.dp, pressedElevation = 0.dp),
        modifier = modifier.widthIn(max = 184.dp).fillMaxWidth().height(52.dp).graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    ) {
        Text(if (running) "Running..." else "Run", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}
