package crow.wasmline.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import crow.wasmline.sample.extensions.toJsonString
import kotlinx.coroutines.launch

@Composable
fun App(wasmPath: String) {
    val scope = rememberCoroutineScope()
    var text: String by remember { mutableStateOf("Value is empty") }
    val wasmLoader = remember { WasmLoader() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.Center
    ) {

        Text(text = text, modifier = Modifier.align(Alignment.CenterHorizontally), fontSize = 18.sp)
        Button(
            onClick = {
                scope.launch {
                    wasmLoader.loadWasm(wasmPath)
                    wasmLoader.timeSync().also { text = toJsonString(it) }
                }
            },
            modifier = Modifier
                .clip(shape = RoundedCornerShape(10.dp))
                .padding(all = 10.dp)
                .align(Alignment.CenterHorizontally),
        ) {
            Text("Load wasm file")
        }
    }
}