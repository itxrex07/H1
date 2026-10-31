package com.hollow.app.isolation.feature.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hollow.app.isolation.ui.components.AuditActionBadge
import com.hollow.app.isolation.ui.components.LsposedStatusBanner
import com.hollow.app.isolation.ui.viewmodel.AppViewModelProvider
import com.hollow.app.isolation.util.friendlyPathSummary
import com.hollow.app.ui.theme.JakartaFontFamily

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel(factory = AppViewModelProvider.Factory),
    onNavigateToApps: () -> Unit,
    onNavigateToAppHider: () -> Unit = {},
    onNavigateToDeviceSpoofer: () -> Unit = {},
    onNavigateToVault: () -> Unit,
    onNavigateToFileManager: () -> Unit = onNavigateToVault,
    onNavigateToGallery: () -> Unit = onNavigateToVault,
    onNavigateToTerminal: () -> Unit,
    onNavigateToAudit: () -> Unit,
    onNavigateToVpnProxy: () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    val isLsposedActive by viewModel.isLsposedActive.collectAsState()
    val isolatedCount by viewModel.isolatedCount.collectAsState()
    val hiddenOrDisguisedCount by viewModel.hiddenOrDisguisedCount.collectAsState()
    val identitySpoofedCount by viewModel.identitySpoofedCount.collectAsState()
    val recentLogs by viewModel.recentAuditLogs.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val allApps by viewModel.allInstalledApps.collectAsState()

    val routedAppsCount = allApps.count { it.vpnProxyEnabled }
    val activeServer = settings.activeServer
    val isVpnOn = settings.vpnTunnelEnabled

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("dashboard_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(2.dp))
            LsposedStatusBanner(
                isActive = isLsposedActive,
                onToggle = { viewModel.refreshDiagnostics() }
            )
        }

        // Section Label: Core Protection Modules (Bento 2x2 Grid)
        item {
            Text(
                text = "PROTECTION MODULES",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = scheme.onSurfaceVariant,
                fontFamily = JakartaFontFamily,
                modifier = Modifier.padding(top = 2.dp, start = 2.dp)
            )
        }

        // Bento Row 1: Storage Isolate + Privacy & ID
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BentoModuleTile(
                    title = "Storage Isolate",
                    subtitle = if (isolatedCount == 0) "Sandbox app files & media" else "$isolatedCount app${if (isolatedCount == 1) "" else "s"} isolated",
                    badgeText = "$isolatedCount",
                    icon = Icons.Default.Apps,
                    accentColor = scheme.primary,
                    onClick = onNavigateToApps,
                    testTag = "stat_isolated_apps",
                    modifier = Modifier.weight(1f)
                )

                BentoModuleTile(
                    title = "Privacy & ID",
                    subtitle = if (identitySpoofedCount == 0) "IMEI, Build, GPS & Shields" else "$identitySpoofedCount app${if (identitySpoofedCount == 1) "" else "s"} cloaked",
                    badgeText = "$identitySpoofedCount",
                    icon = Icons.Default.Fingerprint,
                    accentColor = scheme.tertiary,
                    onClick = onNavigateToDeviceSpoofer,
                    testTag = "card_device_identity_spoofer",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Bento Row 2: Hide App + Proxy & V2Ray (Clean navigation tile, no inline toggle switch)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BentoModuleTile(
                    title = "Hide App",
                    subtitle = if (hiddenOrDisguisedCount == 0) "Launcher & SystemUI stealth" else "$hiddenOrDisguisedCount app${if (hiddenOrDisguisedCount == 1) "" else "s"} disguised",
                    badgeText = "$hiddenOrDisguisedCount",
                    icon = Icons.Default.VisibilityOff,
                    accentColor = scheme.secondary,
                    onClick = onNavigateToAppHider,
                    testTag = "card_hide_disguise_apps",
                    modifier = Modifier.weight(1f)
                )

                BentoModuleTile(
                    title = "Proxy & V2Ray",
                    subtitle = when {
                        isVpnOn && settings.connectWholeDevice -> "${activeServer.name} • Whole device"
                        isVpnOn && routedAppsCount > 0 -> "${activeServer.name} • $routedAppsCount apps"
                        isVpnOn -> "${activeServer.name} • Tunnel verified"
                        else -> "Import a VLESS node to connect"
                    },
                    badgeText = if (isVpnOn) "Connected" else "Disconnected",
                    icon = Icons.Default.Public,
                    accentColor = Color(0xFF0EA5E9),
                    isGlowEnabled = isVpnOn,
                    onClick = onNavigateToVpnProxy,
                    testTag = "dashboard_vpn_card",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Section Label: Activity & Diagnostics
        item {
            Text(
                text = "AUDIT & RECENT EVENTS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = scheme.onSurfaceVariant,
                fontFamily = JakartaFontFamily,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp)
            )
        }

        // Horizontal Activity Log Action Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = scheme.surface),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, scheme.outlineVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onNavigateToAudit() }
                    .testTag("dashboard_activity_log_card")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(scheme.primaryContainer)
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "Activity Log",
                            tint = scheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Activity Log",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = scheme.onSurface,
                                fontFamily = JakartaFontFamily
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(scheme.primary.copy(alpha = 0.14f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${recentLogs.size} events",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = scheme.primary,
                                    fontFamily = JakartaFontFamily
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (recentLogs.isEmpty()) "Live security audit trail is clear" else "Tap to inspect intercepted paths & timestamps",
                            fontSize = 12.sp,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = JakartaFontFamily
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Open Activity Log",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Recent Activity Header + Grouped Container
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .testTag("stat_interceptions")
            ) {
                Text(
                    text = "RECENT ACTIVITY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    fontFamily = JakartaFontFamily,
                    color = scheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { viewModel.refreshDiagnostics() },
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("btn_resync")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onNavigateToAudit() }
                    ) {
                        Text(
                            text = "See all",
                            fontSize = 12.sp,
                            color = scheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = JakartaFontFamily
                        )
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = scheme.surface),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, scheme.outlineVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (recentLogs.isEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(scheme.primaryContainer)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = scheme.onPrimaryContainer,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "All quiet right now",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = JakartaFontFamily,
                                color = scheme.onSurface
                            )
                            Text(
                                text = "Protected storage and identity events will appear here in real time.",
                                color = scheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                fontFamily = JakartaFontFamily
                            )
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val previewLogs = recentLogs.take(4)
                        previewLogs.forEachIndexed { index, log ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToAudit() }
                                    .padding(horizontal = 14.dp, vertical = 11.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(scheme.surfaceContainerLow)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = null,
                                        tint = scheme.primary,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(11.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = log.appName,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                            color = scheme.onSurface,
                                            fontFamily = JakartaFontFamily,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        AuditActionBadge(action = log.action)
                                    }
                                    Spacer(modifier = Modifier.height(1.dp))
                                    Text(
                                        text = friendlyPathSummary(log.requestedPath),
                                        fontSize = 11.sp,
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            if (index < previewLogs.lastIndex) {
                                HorizontalDivider(
                                    color = scheme.outlineVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(horizontal = 14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BentoModuleTile(
    title: String,
    subtitle: String,
    badgeText: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    isGlowEnabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val effectiveColor = if (isGlowEnabled) accentColor else scheme.onSurfaceVariant
    val iconBgColor = if (isGlowEnabled) accentColor.copy(alpha = 0.16f) else scheme.surfaceContainerHigh
    val badgeBgColor = if (isGlowEnabled) accentColor.copy(alpha = 0.14f) else scheme.surfaceContainerHigh
    Card(
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .testTag(testTag)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isGlowEnabled) {
                        Modifier.background(
                            Brush.verticalGradient(
                                listOf(
                                    accentColor.copy(alpha = 0.10f),
                                    Color.Transparent
                                )
                            )
                        )
                    } else Modifier
                )
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconBgColor)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = effectiveColor,
                        modifier = Modifier.size(21.dp)
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(badgeBgColor)
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = badgeText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = effectiveColor,
                            fontFamily = JakartaFontFamily
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = effectiveColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = JakartaFontFamily
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = JakartaFontFamily
            )
        }
    }
}
