package com.hollow.app.isolation.feature.audit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hollow.app.isolation.model.AuditAction
import com.hollow.app.isolation.ui.components.AuditActionBadge
import com.hollow.app.isolation.feature.dashboard.DashboardViewModel
import com.hollow.app.isolation.ui.viewmodel.AppViewModelProvider
import com.hollow.app.isolation.util.friendlyPathSummary
import com.hollow.app.ui.theme.JakartaFontFamily

@Composable
fun AuditLogsScreen(
    viewModel: DashboardViewModel = viewModel(factory = AppViewModelProvider.Factory),
    onBack: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val auditLogs by viewModel.recentAuditLogs.collectAsState()
    var selectedFilter by remember { mutableStateOf("ALL") }

    val filteredLogs = when (selectedFilter) {
        "BLOCKED" -> auditLogs.filter { it.action == AuditAction.BLOCKED }
        "ENCRYPTED" -> auditLogs.filter { it.action == AuditAction.ENCRYPTED }
        "REDIRECTED" -> auditLogs.filter { it.action == AuditAction.REDIRECTED }
        "SPOOFED" -> auditLogs.filter { it.action == AuditAction.SPOOFED_EMPTY }
        else -> auditLogs
    }

    val filterLabels = listOf(
        "ALL" to "All",
        "REDIRECTED" to "Kept Private",
        "SPOOFED" to "Hidden Gallery",
        "BLOCKED" to "Blocked Clutter"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("audit_logs_screen")
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = scheme.onBackground
                        )
                    }
                }
                Column {
                    Text(
                        text = "Activity",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        fontFamily = JakartaFontFamily,
                        color = scheme.onBackground
                    )
                    Text(
                        text = "See when apps check your photos or save files.",
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                        fontFamily = JakartaFontFamily
                    )
                }
            }
            if (auditLogs.isNotEmpty()) {
                IconButton(onClick = { viewModel.clearAuditLogs() }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear History",
                        tint = scheme.error
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(filterLabels) { (key, label) ->
                val isSelected = selectedFilter == key
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedFilter = key },
                    label = { Text(label, fontFamily = JakartaFontFamily, fontSize = 12.sp) },
                    shape = RoundedCornerShape(50),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = scheme.primaryContainer,
                        selectedLabelColor = scheme.onPrimaryContainer,
                        containerColor = scheme.surface,
                        labelColor = scheme.onSurfaceVariant
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(scheme.surfaceContainerLow)
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No activity yet",
                        color = scheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = JakartaFontFamily
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "When protected apps access storage, you'll see it here.",
                        color = scheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontFamily = JakartaFontFamily
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filteredLogs, key = { it.id }) { log ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = scheme.surface),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, scheme.outlineVariant),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(14.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(scheme.surfaceContainerLow)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = scheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = log.appName,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                            color = scheme.onSurface,
                                            fontFamily = JakartaFontFamily,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = android.text.format.DateFormat.format("h:mm a", log.timestamp).toString(),
                                            fontSize = 11.sp,
                                            color = scheme.onSurfaceVariant,
                                            fontFamily = JakartaFontFamily,
                                            softWrap = false
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    AuditActionBadge(action = log.action)
                                }

                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = friendlyPathSummary(log.requestedPath),
                                    fontSize = 12.sp,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontFamily = JakartaFontFamily
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}
