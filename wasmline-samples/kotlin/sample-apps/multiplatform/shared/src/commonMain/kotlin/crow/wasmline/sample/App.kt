package crow.wasmline.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import crow.wasmline.sample.extensions.getPlatformBean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class OutputTab {
    Result,
    Request,
    Log,
}

/** Owns screen state and turns UI events into runtime requests. */
@Composable
fun App(
    wasmPath: String,
    autoExecute: Boolean = false,
    execDispatcher: CoroutineDispatcher = Dispatchers.Main,
    assetRefresher: AssetRefresher = NoOpAssetRefresher,
    artifacts: SampleArtifacts = SampleArtifacts(coreServicePath = wasmPath),
) {
    val scope = rememberCoroutineScope()
    val runner = remember(assetRefresher) { WasmSampleRunner(assetRefresher) }
    val basePlatformBean = remember { getPlatformBean() }

    var mode by remember { mutableStateOf(WasmSampleMode.CORE_SERVICE) }
    var coreServicePath by remember(artifacts.coreServicePath) { mutableStateOf(artifacts.coreServicePath) }
    var rawExportPath by remember(artifacts.rawExportPath) { mutableStateOf(artifacts.rawExportPath) }
    var componentServicePath by remember(artifacts.componentServicePath) { mutableStateOf(artifacts.componentServicePath) }
    var componentFixturePath by remember(artifacts.componentFixturePath) { mutableStateOf(artifacts.componentFixturePath) }
    var componentExportPath by remember(artifacts.componentExportPath) { mutableStateOf(artifacts.componentExportPath) }
    var content by remember { mutableStateOf(basePlatformBean.content) }
    var rawValue by remember { mutableStateOf("21") }
    var forceReload by remember { mutableStateOf(false) }
    var freshMode by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(OutputTab.Result) }
    var report by remember {
        mutableStateOf(WasmExecutionReport.idle(mode, artifacts.pathFor(mode)))
    }

    val artifactPath = when (mode) {
        WasmSampleMode.CORE_SERVICE -> coreServicePath
        WasmSampleMode.RAW_EXPORT -> rawExportPath
        WasmSampleMode.COMPONENT_SERVICE -> componentServicePath
        WasmSampleMode.COMPONENT_FIXTURE -> componentFixturePath
        WasmSampleMode.COMPONENT_EXPORT -> componentExportPath
    }

    fun updateMode(nextMode: WasmSampleMode) {
        mode = nextMode
        activeTab = OutputTab.Result
        report = WasmExecutionReport.idle(nextMode, when (nextMode) {
            WasmSampleMode.CORE_SERVICE -> coreServicePath
            WasmSampleMode.RAW_EXPORT -> rawExportPath
            WasmSampleMode.COMPONENT_SERVICE -> componentServicePath
            WasmSampleMode.COMPONENT_FIXTURE -> componentFixturePath
            WasmSampleMode.COMPONENT_EXPORT -> componentExportPath
        })
    }

    fun updateArtifactPath(path: String) {
        when (mode) {
            WasmSampleMode.CORE_SERVICE -> coreServicePath = path
            WasmSampleMode.RAW_EXPORT -> rawExportPath = path
            WasmSampleMode.COMPONENT_SERVICE -> componentServicePath = path
            WasmSampleMode.COMPONENT_FIXTURE -> componentFixturePath = path
            WasmSampleMode.COMPONENT_EXPORT -> componentExportPath = path
        }
        if (report.status != WasmExecutionStatus.Running) {
            report = WasmExecutionReport.idle(mode, path)
        }
    }

    fun execute() {
        if (report.status == WasmExecutionStatus.Running) return

        val request = WasmExecutionRequest(
            mode = mode,
            artifactPath = artifactPath,
            platform = basePlatformBean.platform,
            content = content,
            rawValue = rawValue.toIntOrNull() ?: 0,
            timeOffsetMs = 0,
            forceReload = forceReload,
            freshMode = freshMode,
        )
        report = WasmExecutionReport.running(mode, artifactPath)
        scope.launch(execDispatcher) {
            report = runner.execute(request)
        }
    }

    LaunchedEffect(autoExecute) {
        if (autoExecute) execute()
    }

    DisposableEffect(runner) {
        onDispose { runner.close() }
    }

    SampleUI(
        state = SampleScreenState(
            mode = mode,
            artifactPath = artifactPath,
            content = content,
            rawValue = rawValue,
            forceReload = forceReload,
            freshMode = freshMode,
            activeTab = activeTab,
            previewPayload = basePlatformBean.copy(content = content.ifBlank { basePlatformBean.content }),
            report = report,
        ),
        onModeChange = ::updateMode,
        onArtifactPathChange = ::updateArtifactPath,
        onContentChange = { content = it },
        onRawValueChange = { rawValue = it },
        onForceReloadChange = { forceReload = it },
        onFreshModeChange = { freshMode = it },
        onTabChange = { activeTab = it },
        onExecute = ::execute,
    )
}
