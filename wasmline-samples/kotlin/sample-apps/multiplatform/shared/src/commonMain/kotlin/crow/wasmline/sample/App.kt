@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import crow.wasmline.sample.extensions.getPlatformBean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Output tab identifiers for the console display.
 */
enum class OutputTab { Result, Request, Log }

/**
 * Wasmline Sample main application entry point Composable.
 * 
 * This function manages UI-level state only and delegates all WASM business logic to WasmLoader.
 * It does NOT contain any WASM runtime code - pure UI coordination layer.
 * 
 * @param wasmPath Path to the WASM file
 * @param autoExecute Whether to automatically execute WASM on startup
 * @param execDispatcher Coroutine dispatcher for WASM execution
 * @param assetRefresher Optional asset refresher for supporting Fresh Mode
 */
@Composable
fun App(
    wasmPath: String,
    autoExecute: Boolean = false,
    execDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Main,
    assetRefresher: AssetRefresher = NoOpAssetRefresher,
) {
    val scope = rememberCoroutineScope()
    val basePlatformBean = remember { getPlatformBean() }
    
    // UI-related state only (no WASM logic here)
    var contentLabel by remember { mutableStateOf(basePlatformBean.content) }
    var artifactPath by remember(wasmPath) { mutableStateOf(wasmPath) }
    var forceReload by remember { mutableStateOf(false) }
    var freshMode by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(OutputTab.Result) }
    var report by remember { mutableStateOf(WasmExecutionReport.idle(artifactPath)) }
    
    val previewPayload = remember(contentLabel, basePlatformBean) {
        basePlatformBean.copy(content = contentLabel.ifBlank { basePlatformBean.content })
    }
    
    // Render dedicated UI component with all necessary callbacks
    SampleUI(
        previewPayload = previewPayload,
        artifactPath = artifactPath,
        contentLabel = contentLabel,
        activeTab = activeTab.name,
        forceReload = forceReload,
        freshMode = freshMode,
        report = report,
        onArtifactPathChange = { artifactPath = it },
        onContentChange = { contentLabel = it },
        onForceReloadChange = { forceReload = it },
        onFreshModeChange = { freshMode = it },
        onTabChange = { activeTab = OutputTab.valueOf(it) },
        onExecute = { request ->
            scope.launch(execDispatcher) {
                val executedReport = WasmLoader().execute(request)
                report = executedReport
                println("[App] Execution completed: ${executedReport.status}")
            }
        },
    )
}