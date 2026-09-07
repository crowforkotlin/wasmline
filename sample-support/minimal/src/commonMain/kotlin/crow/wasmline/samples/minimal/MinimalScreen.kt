package crow.wasmline.samples.minimal

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MinimalScreen(state: RunState, onRun: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = Color(0xFFF7F8FA), modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp).semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Crossfade(targetState = state, animationSpec = tween(240)) { displayed ->
                        SelectionContainer {
                            Text(
                                text = displayed.text,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                color = if (displayed.status == RunStatus.Failure) Color(0xFFB42338) else Color(0xFF20242B),
                                fontSize = if (displayed.status == RunStatus.Failure) 14.sp else 26.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                RunButton(running = state.status == RunStatus.Running, onClick = onRun)
            }
        }
    }
}
