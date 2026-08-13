package crow.wasmline.sample

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import crow.wasmline.sample.bean.PlatformBean
import crow.wasmline.sample.extensions.toJsonString

private val PageBackground = Color(0xFFF6F7F9)
private val PanelColor = Color(0xFFFFFFFF)
private val Ink = Color(0xFF17202A)
private val MutedInk = Color(0xFF65727E)
private val Border = Color(0xFFE0E5EA)
private val Green = Color(0xFF168A65)
private val Amber = Color(0xFFB26A00)
private val Coral = Color(0xFFC24D4D)
private val Purple = Color(0xFF6B5CC5)
private val CodeBackground = Color(0xFF20262D)
private val CodeForeground = Color(0xFFB7E5D2)

data class SampleScreenState(
    val mode: WasmSampleMode,
    val artifactPath: String,
    val content: String,
    val rawValue: String,
    val forceReload: Boolean,
    val freshMode: Boolean,
    val activeTab: OutputTab,
    val previewPayload: PlatformBean,
    val report: WasmExecutionReport,
)

@Composable
fun SampleUI(
    state: SampleScreenState,
    onModeChange: (WasmSampleMode) -> Unit,
    onArtifactPathChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onRawValueChange: (String) -> Unit,
    onForceReloadChange: (Boolean) -> Unit,
    onFreshModeChange: (Boolean) -> Unit,
    onTabChange: (OutputTab) -> Unit,
    onExecute: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBackground)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(report = state.report)
        ModeSelector(selected = state.mode, onSelect = onModeChange)

        RequestPanel(
            state = state,
            onArtifactPathChange = onArtifactPathChange,
            onContentChange = onContentChange,
            onRawValueChange = onRawValueChange,
            onForceReloadChange = onForceReloadChange,
            onFreshModeChange = onFreshModeChange,
        )

        Button(
            onClick = onExecute,
            enabled = state.report.status != WasmExecutionStatus.Running,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Ink,
                contentColor = Color.White,
                disabledContainerColor = MutedInk,
            ),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Run sample")
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (state.report.status == WasmExecutionStatus.Running) "Running" else "Run sample",
                fontWeight = FontWeight.SemiBold,
            )
        }

        Metrics(report = state.report)
        ResultPanel(
            state = state,
            onTabChange = onTabChange,
        )
    }
}

@Composable
private fun Header(report: WasmExecutionReport) {
    val accent = statusColor(report.status)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(10.dp),
                color = Ink,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = "Wasmline",
                        tint = Color.White,
                    )
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Wasmline samples",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
                Text(
                    text = "Four packaged contracts plus a cross-language Component fixture",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedInk,
                )
            }
        }
        StatusPill(
            label = report.status.name.lowercase(),
            color = accent,
            icon = when (report.status) {
                WasmExecutionStatus.Idle -> Icons.Default.Schedule
                WasmExecutionStatus.Running -> Icons.Default.Schedule
                WasmExecutionStatus.Success -> Icons.Default.CheckCircle
                WasmExecutionStatus.Failure -> Icons.Default.ErrorOutline
            },
        )
    }
}

@Composable
private fun ModeSelector(
    selected: WasmSampleMode,
    onSelect: (WasmSampleMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(text = "Invocation model")
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WasmSampleMode.entries.forEach { mode ->
                val selectedMode = mode == selected
                Surface(
                    onClick = { onSelect(mode) },
                    modifier = Modifier.width(196.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = if (selectedMode) Color(0xFFEAF5F0) else PanelColor,
                    border = BorderStroke(1.dp, if (selectedMode) Green else Border),
                ) {
                    Row(
                        modifier = Modifier.padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = when (mode) {
                                WasmSampleMode.CORE_SERVICE -> Icons.Default.Memory
                                WasmSampleMode.RAW_EXPORT -> Icons.Default.Code
                                WasmSampleMode.COMPONENT_SERVICE,
                                WasmSampleMode.COMPONENT_FIXTURE,
                                WasmSampleMode.COMPONENT_EXPORT,
                                -> Icons.Default.AccountTree
                            },
                            contentDescription = mode.title,
                            tint = if (selectedMode) Green else MutedInk,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = mode.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Ink,
                            )
                            Text(
                                text = mode.protocol,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selectedMode) Green else MutedInk,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestPanel(
    state: SampleScreenState,
    onArtifactPathChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onRawValueChange: (String) -> Unit,
    onForceReloadChange: (Boolean) -> Unit,
    onFreshModeChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = PanelColor,
        border = BorderStroke(1.dp, Border),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Request",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink,
                    )
                    Text(
                        text = state.mode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedInk,
                    )
                }
                Text(
                    text = state.mode.defaultExport,
                    style = MaterialTheme.typography.labelSmall,
                    color = Purple,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Field(
                label = "Artifact",
                value = state.artifactPath,
                placeholder = "Path to .wasm, .pwasm, .cwasm, or .wlm",
                monospace = true,
                onValueChange = onArtifactPathChange,
            )

            if (state.mode.usesNumericInput) {
                Field(
                    label = "i32 input",
                    value = state.rawValue,
                    placeholder = "21",
                    monospace = true,
                    onValueChange = onRawValueChange,
                )
            } else {
                Field(
                    label = "Content",
                    value = state.content,
                    placeholder = "hello",
                    onValueChange = onContentChange,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                SettingRow(
                    label = "Force reload",
                    checked = state.forceReload,
                    onCheckedChange = onForceReloadChange,
                )
                SettingRow(
                    label = "Fresh asset",
                    checked = state.freshMode,
                    onCheckedChange = onFreshModeChange,
                )
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    monospace: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MutedInk)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(placeholder, color = MutedInk) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = Ink,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            ),
            shape = RoundedCornerShape(7.dp),
        )
    }
}

@Composable
private fun SettingRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val containerShape = RoundedCornerShape(7.dp)
    val checkboxShape = RoundedCornerShape(5.dp)
    Row(
        modifier = Modifier
            .clip(containerShape)
            .background(if (checked) Green.copy(alpha = 0.08f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (checked) Green.copy(alpha = 0.4f) else Border,
                shape = containerShape,
            )
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(checkboxShape)
                .background(if (checked) Green else Color.Transparent)
                .border(
                    width = 1.dp,
                    color = if (checked) Green else MutedInk,
                    shape = checkboxShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Color.White,
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Metrics(report: WasmExecutionReport) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Metric(modifier = Modifier.weight(1f), label = "Load", value = formatDuration(report.loadDurationMs))
        Metric(modifier = Modifier.weight(1f), label = "Invoke", value = formatDuration(report.invokeDurationMs))
        Metric(modifier = Modifier.weight(1f), label = "Total", value = formatDuration(report.totalDurationMs))
    }
}

@Composable
private fun Metric(modifier: Modifier, label: String, value: String) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = PanelColor,
        border = BorderStroke(1.dp, Border),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MutedInk)
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
            )
        }
    }
}

@Composable
private fun ResultPanel(
    state: SampleScreenState,
    onTabChange: (OutputTab) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = PanelColor,
        border = BorderStroke(1.dp, Border),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 15.dp, end = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = state.report.headline,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink,
                    )
                    Text(
                        text = state.report.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedInk,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(
                    label = state.report.mode.protocol,
                    color = statusColor(state.report.status),
                    icon = Icons.Default.Code,
                )
            }

            TabRow(
                selectedTabIndex = state.activeTab.ordinal,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                OutputTab.entries.forEach { tab ->
                    Tab(
                        selected = state.activeTab == tab,
                        onClick = { onTabChange(tab) },
                        text = { Text(tab.name) },
                    )
                }
            }

            val content = when (state.activeTab) {
                OutputTab.Result -> state.report.outputJson.ifBlank {
                    state.report.errorMessage.ifBlank { "// no result yet\n${toJsonString(state.previewPayload)}" }
                }

                OutputTab.Request -> state.report.inputJson.ifBlank { toJsonString(state.previewPayload) }
                OutputTab.Log -> state.report.consoleLog
            }
            SelectionContainer {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 210.dp),
                    color = CodeBackground,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        Text(
                            text = content,
                            color = CodeForeground,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.report.artifactName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${state.report.loadModeLabel} / ${state.report.backendLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedInk,
                    maxLines = 1,
                )
                TextButton(onClick = { onTabChange(OutputTab.Log) }) {
                    Text("View logs")
                }
            }
        }
    }
}

@Composable
private fun StatusPill(
    label: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(15.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MutedInk,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun statusColor(status: WasmExecutionStatus): Color = when (status) {
    WasmExecutionStatus.Idle -> MutedInk
    WasmExecutionStatus.Running -> Amber
    WasmExecutionStatus.Success -> Green
    WasmExecutionStatus.Failure -> Coral
}

private fun formatDuration(ms: Long): String = when {
    ms <= 0L -> "0 ms"
    ms < 1_000L -> "$ms ms"
    else -> "${ms / 1000}.${(ms % 1000) / 10}s"
}
