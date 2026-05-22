package crow.wasmline.sample

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import crow.wasmline.sample.bean.PlatformBean
import crow.wasmline.sample.extensions.getPlatformBean
import crow.wasmline.sample.extensions.toJsonString
import kotlinx.coroutines.launch

// ─── Constants ────────────────────────────────────────────────────────────────

private enum class OutputTab { Result, Request, Log }

// ─── Design tokens ────────────────────────────────────────────────────────────

private val CoolGrayBg = Color(0xFFF1F5F9)
private val White      = Color(0xFFFFFFFF)
private val Slate900   = Color(0xFF0F172A)
private val Slate700   = Color(0xFF334155)
private val Slate500   = Color(0xFF64748B)
private val Slate200   = Color(0xFFE2E8F0)
private val Slate100   = Color(0xFFF1F5F9)
private val Indigo600  = Color(0xFF4F46E5)
private val Green500   = Color(0xFF10B981)
private val Red500     = Color(0xFFEF4444)
private val SkyBlue    = Color(0xFF38BDF8)
private val NavyDark   = Color(0xFF0F172A)

// ─── Root ─────────────────────────────────────────────────────────────────────

@Composable
fun App(wasmPath: String) {
    val scope            = rememberCoroutineScope()
    val basePlatformBean = remember { getPlatformBean() }
    val wasmLoader       = remember { WasmLoader() }

    var artifactPath by remember(wasmPath) { mutableStateOf(wasmPath) }
    var contentLabel by remember { mutableStateOf(basePlatformBean.content) }
    var forceReload  by remember { mutableStateOf(false) }
    var activeTab    by remember { mutableStateOf(OutputTab.Result) }
    var report       by remember(wasmPath) { mutableStateOf(WasmExecutionReport.idle(wasmPath)) }

    val previewPayload = remember(contentLabel) {
        basePlatformBean.copy(content = contentLabel.ifBlank { basePlatformBean.content })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CoolGrayBg)
            .safeDrawingPadding()
            .imePadding()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppHeader(report = report)

        InputCard(
            report               = report,
            contentLabel         = contentLabel,
            artifactPath         = artifactPath,
            forceReload          = forceReload,
            onContentChange      = { contentLabel = it },
            onArtifactPathChange = { artifactPath = it },
            onForceReloadChange  = { forceReload  = it },
        )

        ExecuteButton(
            isRunning = report.status == WasmExecutionStatus.Running,
            onExecute = {
                scope.launch {
                    report = WasmExecutionReport.running(artifactPath)
                    report = wasmLoader.execute(
                        WasmExecutionRequest(
                            artifactPath = artifactPath,
                            platform     = basePlatformBean.platform,
                            content      = contentLabel,
                            timeOffsetMs = 0L,
                            forceReload  = forceReload,
                        )
                    )
                }
            },
        )

        MetricsRow(report = report)

        ConsoleCard(
            modifier       = Modifier.weight(1f),
            report         = report,
            previewPayload = previewPayload,
            activeTab      = activeTab,
            onTabChange    = { activeTab = it },
        )
    }
}

// ─── Header ───────────────────────────────────────────────────────────────────

@Composable
private fun AppHeader(report: WasmExecutionReport) {
    val dotColor = when (report.status) {
        WasmExecutionStatus.Running, WasmExecutionStatus.Success -> Green500
        WasmExecutionStatus.Failure                              -> Red500
        WasmExecutionStatus.Idle                                 -> Slate500
    }

    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape)
        )
        Text(
            text       = "Wasmline",
            style      = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color      = Slate900,
        )
    }
}

@Composable
private fun MetaBadge(label: String, value: String, monospace: Boolean = false) {
    Surface(
        shape  = RoundedCornerShape(999.dp),
        color  = White,
        border = BorderStroke(1.dp, Slate200),
    ) {
        Row(
            modifier             = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment    = Alignment.CenterVertically,
        ) {
            Text(
                text  = label,
                style = MaterialTheme.typography.labelSmall,
                color = Slate500,
            )
            Text(
                text       = value,
                style      = MaterialTheme.typography.labelSmall,
                color      = Slate700,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
        }
    }
}

// ─── Input Card ───────────────────────────────────────────────────────────────

@Composable
private fun InputCard(
    report              : WasmExecutionReport,
    contentLabel        : String,
    artifactPath        : String,
    forceReload         : Boolean,
    onContentChange     : (String) -> Unit,
    onArtifactPathChange: (String) -> Unit,
    onForceReloadChange : (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        color    = White,
        border   = BorderStroke(1.dp, Slate200),
    ) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ForceReload chip + file badge in same scrollable row
            val badgeScroll = rememberScrollState()
            Row(
                modifier              = Modifier.horizontalScroll(badgeScroll),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                ForceReloadChip(checked = forceReload, onToggle = onForceReloadChange)
                val fileName = artifactPath
                    .substringAfterLast('/').substringAfterLast('\\').ifBlank { "—" }
                MetaBadge(label = "file", value = fileName)
            }

            // Payload content
            FieldLabel(text = "Payload")
            InlineTextField(
                value         = contentLabel,
                onValueChange = onContentChange,
                placeholder   = "Describe the request payload",
            )

            // Artifact path
            FieldLabel(text = "Artifact Path")
            InlineTextField(
                value         = artifactPath,
                onValueChange = onArtifactPathChange,
                placeholder   = "Path to .wasm / .pwasm file",
                monospace     = true,
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text    = text,
        style   = MaterialTheme.typography.labelSmall,
        color   = Slate500,
        maxLines = 1,
    )
}

@Composable
private fun ForceReloadChip(checked: Boolean, onToggle: (Boolean) -> Unit) {
    val bgColor     = if (checked) Color(0xFFFFF7ED) else Slate100
    val borderColor = if (checked) Color(0xFFFBBF24) else Slate200
    val textColor   = if (checked) Color(0xFF92400E) else Slate500
    val dotColor    = if (checked) Color(0xFFFBBF24) else Slate200
    Surface(
        onClick  = { onToggle(!checked) },
        shape    = RoundedCornerShape(999.dp),
        color    = bgColor,
        border   = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier              = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(6.dp).background(dotColor, CircleShape))
            Text(
                text     = "Force Reload",
                style    = MaterialTheme.typography.labelSmall,
                color    = textColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun InlineTextField(
    value       : String,
    onValueChange: (String) -> Unit,
    placeholder : String,
    monospace   : Boolean = false,
) {
    TextField(
        value       = value,
        onValueChange = onValueChange,
        modifier    = Modifier.fillMaxWidth(),
        placeholder = {
            Text(
                text     = placeholder,
                style    = MaterialTheme.typography.bodySmall,
                color    = Slate500,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        singleLine  = true,
        shape       = RoundedCornerShape(8.dp),
        textStyle   = MaterialTheme.typography.bodySmall.copy(
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            color      = Slate900,
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor    = Slate100,
            unfocusedContainerColor  = Slate100,
            disabledContainerColor   = Slate100,
            focusedIndicatorColor    = Color.Transparent,
            unfocusedIndicatorColor  = Color.Transparent,
            disabledIndicatorColor   = Color.Transparent,
            cursorColor              = Indigo600,
            focusedTextColor         = Slate900,
            unfocusedTextColor       = Slate900,
        ),
    )
}

// ─── Execute Button ───────────────────────────────────────────────────────────

@Composable
private fun ExecuteButton(isRunning: Boolean, onExecute: () -> Unit) {
    Button(
        onClick  = onExecute,
        enabled  = !isRunning,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape  = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor         = Slate900,
            contentColor           = White,
            disabledContainerColor = Slate500,
            disabledContentColor   = White,
        ),
    ) {
        Text(
            text       = if (isRunning) "Running…" else "Execute  →",
            style      = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines   = 1,
        )
    }
}

// ─── Metrics Row ─────────────────────────────────────────────────────────────

@Composable
private fun MetricsRow(report: WasmExecutionReport) {
    Row(
        modifier             = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetricCell(modifier = Modifier.weight(1f), label = "Load",   value = formatDuration(report.loadDurationMs))
        MetricCell(modifier = Modifier.weight(1f), label = "Invoke", value = formatDuration(report.invokeDurationMs))
        MetricCell(modifier = Modifier.weight(1f), label = "Total",  value = formatDuration(report.totalDurationMs), accent = Green500)
    }
}

@Composable
private fun MetricCell(
    label   : String,
    value   : String,
    modifier: Modifier = Modifier,
    accent  : Color = Slate900,
) {
    Surface(
        modifier = modifier,
        shape    = RoundedCornerShape(12.dp),
        color    = White,
        border   = BorderStroke(1.dp, Slate200),
    ) {
        Column(
            modifier             = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = Slate500)
            Text(
                text       = value,
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color      = accent,
            )
        }
    }
}

// ─── Console Card ─────────────────────────────────────────────────────────────

@Composable
private fun ConsoleCard(
    modifier      : Modifier,
    report        : WasmExecutionReport,
    previewPayload: PlatformBean,
    activeTab     : OutputTab,
    onTabChange   : (OutputTab) -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        color    = NavyDark,
    ) {
        Column(
            modifier             = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Tab bar + runtime status tag
            Row(
                modifier             = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment    = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutputTab.entries.forEach { tab ->
                        ConsoleTabChip(
                            text     = tab.displayName(),
                            selected = tab == activeTab,
                            onClick  = { onTabChange(tab) },
                        )
                    }
                }
                val (statusLabel, statusColor) = statusInfo(report)
                ConsoleStatusTag(text = statusLabel, accent = statusColor)
            }

            // Scrollable code area — weight(1f) so it fills the remaining console space
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val hScroll = rememberScrollState()
                val vScroll = rememberScrollState()
                SelectionContainer(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(hScroll)
                            .verticalScroll(vScroll)
                    ) {
                        Text(
                            text       = resolveConsoleContent(report, previewPayload, activeTab),
                            color      = SkyBlue,
                            fontFamily = FontFamily.Monospace,
                            style      = MaterialTheme.typography.labelSmall,
                            fontSize   = 10.sp,
                            softWrap   = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsoleTabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape   = RoundedCornerShape(999.dp),
        color   = if (selected) White.copy(alpha = 0.12f) else Color.Transparent,
        border  = BorderStroke(1.dp, if (selected) White.copy(alpha = 0.20f) else Color.Transparent),
    ) {
        Text(
            text     = text,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
            style    = MaterialTheme.typography.labelSmall,
            color    = if (selected) White else Slate500,
            maxLines = 1,
        )
    }
}

@Composable
private fun ConsoleStatusTag(text: String, accent: Color) {
    Surface(
        shape  = RoundedCornerShape(999.dp),
        color  = accent.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.20f)),
    ) {
        Text(
            text     = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style    = MaterialTheme.typography.labelSmall,
            color    = accent,
            maxLines = 1,
        )
    }
}

// ─── Pure helpers ─────────────────────────────────────────────────────────────

private fun OutputTab.displayName(): String = when (this) {
    OutputTab.Result  -> "Result"
    OutputTab.Request -> "Request"
    OutputTab.Log     -> "Log"
}

private fun statusInfo(report: WasmExecutionReport): Pair<String, Color> = when (report.status) {
    WasmExecutionStatus.Idle    -> Pair("idle",    Slate500)
    WasmExecutionStatus.Running -> Pair("running", Green500)
    WasmExecutionStatus.Success -> Pair("success", Green500)
    WasmExecutionStatus.Failure -> Pair("error",   Red500)
}

private fun resolveConsoleContent(
    report        : WasmExecutionReport,
    previewPayload: PlatformBean,
    activeTab     : OutputTab,
): String = when (activeTab) {
    OutputTab.Result  -> report.outputJson.ifBlank {
        if (report.errorMessage.isNotBlank()) {
            "// error\n${report.errorMessage}"
        } else {
            "// waiting for result\n// current request preview:\n${toJsonString(previewPayload)}"
        }
    }
    OutputTab.Request -> report.inputJson.ifBlank { toJsonString(previewPayload) }
    OutputTab.Log     -> report.consoleLog
}

private fun formatDuration(ms: Long): String = when {
    ms <= 0L   -> "0 ms"
    ms < 1_000L -> "$ms ms"
    else        -> "${ms / 1000}.${(ms % 1000) / 10}s"
}
