package com.hollow.app.isolation.feature.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hollow.app.isolation.ui.components.ProSegmentedTabRow
import com.hollow.app.isolation.ui.components.StatusBadge
import com.hollow.app.isolation.feature.settings.SettingsViewModel
import com.hollow.app.isolation.ui.viewmodel.AppViewModelProvider
import com.hollow.app.ui.theme.FrauncesFontFamily
import com.hollow.app.ui.theme.JakartaFontFamily
import com.hollow.app.ui.theme.JetBrainsMonoFontFamily

@Composable
fun MountTerminalScreen(
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val scheme = MaterialTheme.colorScheme
    val terminalLogs by viewModel.terminalLogs.collectAsState()
    val systemMounts by viewModel.systemMounts.collectAsState()
    val isLsposedActive by viewModel.isLsposedActive.collectAsState()

    var activeTab by remember { mutableIntStateOf(0) }
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    LaunchedEffect(terminalLogs.size) {
        if (terminalLogs.isNotEmpty()) {
            listState.animateScrollToItem(terminalLogs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("mount_terminal_screen")
    ) {
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text(
                    text = "System & Mount Terminal",
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    fontFamily = FrauncesFontFamily,
                    color = scheme.onBackground
                )
                Text(
                    text = "Namespace diagnostics, Magisk/KSU boot script & active mounts",
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                    fontFamily = JakartaFontFamily
                )
            }
            StatusBadge(
                text = if (isLsposedActive) "Hooked" else "Standby",
                color = if (isLsposedActive) scheme.primary else scheme.tertiary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        ProSegmentedTabRow(
            options = listOf(
                "Event Log" to Icons.Default.Terminal,
                "Boot Script" to null,
                "Mounts (${systemMounts.size})" to Icons.Default.Storage
            ),
            selectedIndex = activeTab,
            onSelect = { activeTab = it }
        )

        Spacer(modifier = Modifier.height(12.dp))

        when (activeTab) {
            0 -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(scheme.surface)
                        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    LazyColumn(state = listState) {
                        items(terminalLogs) { log ->
                            Text(
                                text = log,
                                color = if (log.contains("BLOCKED") || log.contains("Error")) scheme.error else scheme.onSurface,
                                fontSize = 12.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                modifier = Modifier.padding(vertical = 3.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { viewModel.runRootCheck() },
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_exec_mount")
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Check Root", fontSize = 13.sp, fontFamily = JakartaFontFamily, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = {
                            val text = terminalLogs.joinToString("\n")
                            clipboard.setText(AnnotatedString(text))
                        },
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Log", fontSize = 13.sp, fontFamily = JakartaFontFamily, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            1 -> {
                val script = viewModel.generateBootScript()

                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(scheme.surface)
                            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(18.dp))
                            .padding(14.dp)
                    ) {
                        LazyColumn {
                            item {
                                Text(
                                    text = script,
                                    color = scheme.onSurface,
                                    fontSize = 12.sp,
                                    fontFamily = JetBrainsMonoFontFamily
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { clipboard.setText(AnnotatedString(script)) },
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_copy_magisk_script")
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Boot Service Script", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, fontFamily = JakartaFontFamily)
                    }
                }
            }

            2 -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(systemMounts) { entry ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = scheme.surface),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, scheme.outlineVariant),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = entry.mountPoint,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = scheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        modifier = Modifier.weight(1f)
                                    )
                                    StatusBadge(text = entry.fsType, color = scheme.primary)
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = entry.device,
                                    fontSize = 11.sp,
                                    color = scheme.onSurfaceVariant,
                                    fontFamily = JetBrainsMonoFontFamily
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
