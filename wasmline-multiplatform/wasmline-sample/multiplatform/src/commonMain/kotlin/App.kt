import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun App(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Value", modifier = Modifier.align(Alignment.CenterHorizontally))
        Button(
            onClick = { onClick() },
            modifier = Modifier
                .padding(10.dp)
                .align(Alignment.CenterHorizontally),
        ) {
            Text("Load wasm file")
        }
    }
}