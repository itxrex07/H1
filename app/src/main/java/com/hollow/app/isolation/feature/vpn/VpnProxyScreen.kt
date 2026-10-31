package com.hollow.app.isolation.feature.vpn

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hollow.app.isolation.feature.settings.SettingsViewModel
import com.hollow.app.isolation.model.VpnConnectionState
import com.hollow.app.isolation.model.VpnProtocol
import com.hollow.app.isolation.model.VpnProxyServer
import com.hollow.app.isolation.pkg.InstalledAppInfo
import com.hollow.app.isolation.ui.components.rememberAppIconBitmap
import com.hollow.app.isolation.ui.viewmodel.AppViewModelProvider
import com.hollow.app.ui.theme.JakartaFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnProxyScreen(
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
    onBack: () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val allApps by viewModel.allInstalledApps.collectAsState()

    val isConnected = settings.vpnConnectionState == VpnConnectionState.CONNECTED
    val isConnecting = settings.vpnConnectionState == VpnConnectionState.CONNECTING
    val activeServer = settings.activeServer
    val allServers = settings.allProxyServers
    val routedAppsCount = remember(allApps) { allApps.count { it.vpnProxyEnabled } }

    LaunchedEffect(Unit) {
        viewModel.refreshRootVpnState()
    }

    var showServerSheet by remember { mutableStateOf(false) }
    var showSplitTunnelSheet by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    fun toggleVpn() {
        if (isConnected || isConnecting) {
            viewModel.toggleRootVpn()
            return
        }
        if (activeServer.shareUri.isBlank()) {
            Toast.makeText(context, "Import a supported VLESS link first.", Toast.LENGTH_SHORT).show()
            showServerSheet = true
            return
        }
        if (!settings.connectWholeDevice && routedAppsCount == 0) {
            Toast.makeText(context, "Select apps to route, or enable whole-device routing.", Toast.LENGTH_SHORT).show()
            showSplitTunnelSheet = true
            return
        }
        viewModel.toggleRootVpn()
    }

    // Navigation back handler
    BackHandler {
        when {
            showSplitTunnelSheet -> showSplitTunnelSheet = false
            showServerSheet -> showServerSheet = false
            else -> onBack()
        }
    }

    // Modern glowing halo animation around connect button
    val buttonGlowColor by animateColorAsState(
        targetValue = if (isConnected) scheme.primary else scheme.outlineVariant.copy(alpha = 0.35f),
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "buttonGlowColor"
    )
    val buttonRingSize by animateDpAsState(
        targetValue = if (isConnected) 148.dp else 138.dp,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "buttonRingSize"
    )

    // Infinite pulsing ripple animations when connected
    val infiniteTransition = rememberInfiniteTransition(label = "vpnPulse")
    val pulseScale1 by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale1"
    )
    val pulseAlpha1 by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha1"
    )
    val pulseScale2 by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, delayMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale2"
    )
    val pulseAlpha2 by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, delayMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha2"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .padding(horizontal = 16.dp)
            .testTag("vpn_proxy_screen")
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        // Top Minimalist Header Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(38.dp).testTag("vpn_proxy_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = scheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "Hollow VPN & Proxy",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onBackground,
                        fontFamily = JakartaFontFamily
                    )
                    Text(
                        text = if (isConnected) "Encrypted tunnel active" else "Disconnected",
                        fontSize = 11.sp,
                        color = if (isConnected) scheme.primary else scheme.onSurfaceVariant,
                        fontFamily = JakartaFontFamily
                    )
                }
            }

            // Quick Add Node action button
            IconButton(
                onClick = { showImportDialog = true },
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceContainerHigh)
                    .testTag("btn_import_node")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Server Node",
                    tint = scheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = scheme.surface),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, scheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "VPN ENGINE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = scheme.onSurfaceVariant,
                            fontFamily = JakartaFontFamily
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "ROOT TPROXY",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurface,
                            fontFamily = JakartaFontFamily
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Uses H1's Xray + root TPROXY engine. No Android VPN interface or VPN permission prompt is used.",
                            fontSize = 11.sp,
                            color = scheme.onSurfaceVariant,
                            fontFamily = JakartaFontFamily
                        )
                    }
                }
            }

            // HERO CARD: Status & Big Power Button
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = scheme.surface),
                    shape = RoundedCornerShape(26.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isConnected) scheme.primary.copy(alpha = 0.5f) else scheme.outlineVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        if (isConnected) scheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                                        Color.Transparent
                                    )
                                )
                            )
                            .padding(vertical = 24.dp, horizontal = 20.dp)
                    ) {
                        // Status Badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (isConnected) scheme.primaryContainer
                                    else scheme.surfaceContainerHigh
                                )
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isConnected) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = null,
                                    tint = if (isConnected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isConnected) "Connected" else "Disconnected",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp,
                                    color = if (isConnected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                                    fontFamily = JakartaFontFamily
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Large Modern Circular Power Button with Ambient Ring & Pulsing Ripple Animations
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(buttonRingSize)
                        ) {
                            if (isConnected) {
                                // Radiating pulse ripple 1
                                Box(
                                    modifier = Modifier
                                        .size(118.dp)
                                        .scale(pulseScale1)
                                        .clip(CircleShape)
                                        .border(2.dp, scheme.primary.copy(alpha = pulseAlpha1), CircleShape)
                                        .background(scheme.primary.copy(alpha = pulseAlpha1 * 0.16f))
                                )
                                // Radiating pulse ripple 2
                                Box(
                                    modifier = Modifier
                                        .size(118.dp)
                                        .scale(pulseScale2)
                                        .clip(CircleShape)
                                        .border(1.5.dp, scheme.primary.copy(alpha = pulseAlpha2), CircleShape)
                                        .background(scheme.primary.copy(alpha = pulseAlpha2 * 0.12f))
                                )
                            }

                            // Outer ambient halo
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .border(
                                        width = if (isConnected) 3.dp else 1.5.dp,
                                        color = buttonGlowColor,
                                        shape = CircleShape
                                    )
                                    .background(
                                        if (isConnected) scheme.primary.copy(alpha = 0.08f) else Color.Transparent
                                    )
                            )

                            // Inner Power Button
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(118.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isConnected) scheme.primary else scheme.surfaceContainerHigh
                                    )
                                    .clickable { toggleVpn() }
                                    .testTag("vpn_power_toggle")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PowerSettingsNew,
                                    contentDescription = if (isConnected || isConnecting) "Disconnect" else "Connect",
                                    tint = if (isConnected) scheme.onPrimary else scheme.primary,
                                    modifier = Modifier.size(46.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Verified tunnel connection status
                        Text(
                            text = when {
                                isConnected -> "Tunnel verified"
                                isConnecting -> "Connecting…"
                                settings.vpnConnectionState == VpnConnectionState.ERROR -> "Connection failed"
                                else -> "Tap to connect"
                            },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurface,
                            fontFamily = JakartaFontFamily
                        )
                        Text(
                            text = when {
                                isConnected -> "H1 Xray • root TPROXY • system routing"
                                isConnecting -> "Starting H1 root Xray service"
                                settings.vpnConnectionState == VpnConnectionState.ERROR -> "No tunnel is active; traffic is direct"
                                else -> "No VPN tunnel is active"
                            },
                            fontSize = 12.sp,
                            color = scheme.onSurfaceVariant,
                            fontFamily = JakartaFontFamily
                        )
                        if (settings.vpnConnectionState == VpnConnectionState.ERROR) {
                            Text(
                                text = statusMessage ?: "Root proxy connection failed. Check root access, the imported VLESS link, and network.",
                                fontSize = 12.sp,
                                color = scheme.error,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }

                        if (isConnected) {
                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(12.dp))

                            // Telemetry metrics row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "PROBE RTT",
                                        fontSize = 10.sp,
                                        color = scheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = JakartaFontFamily
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (activeServer.latencyMs > 0) "${activeServer.latencyMs} " else "— ",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = scheme.primary,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = if (activeServer.latencyMs > 0) "ms" else "",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF007AFF),
                                            fontFamily = JakartaFontFamily
                                        )
                                    }
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "PROTOCOL",
                                        fontSize = 10.sp,
                                        color = scheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = JakartaFontFamily
                                    )
                                    Text(
                                        text = activeServer.protocol.badge,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = scheme.onSurface,
                                        fontFamily = JakartaFontFamily
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "ROUTING",
                                        fontSize = 10.sp,
                                        color = scheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = JakartaFontFamily
                                    )
                                    Text(
                                        text = "Root Rules",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = scheme.onSurface,
                                        fontFamily = JakartaFontFamily
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // SERVER LOCATION SELECTOR CARD
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = scheme.surface),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, scheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { showServerSheet = true }
                        .testTag("card_change_server")
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
                                .clip(RoundedCornerShape(12.dp))
                                .background(scheme.primaryContainer)
                        ) {
                            Text(
                                text = activeServer.protocol.badge.take(3),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = scheme.onPrimaryContainer,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeServer.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = scheme.onSurface,
                                fontFamily = JakartaFontFamily
                            )
                            Text(
                                text = "${activeServer.protocol.badge} • ${activeServer.host}:${activeServer.port}",
                                fontSize = 11.sp,
                                color = scheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF007AFF).copy(alpha = 0.12f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (activeServer.latencyMs > 0) "${activeServer.latencyMs} " else "— ",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = scheme.onSurface,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = if (activeServer.latencyMs > 0) "ms" else "",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF007AFF),
                                    fontFamily = JakartaFontFamily
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Change Server",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // PER-APP SPLIT TUNNELING CARD
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = scheme.surface),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, scheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { showSplitTunnelSheet = true }
                        .testTag("card_split_tunneling")
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
                                .clip(RoundedCornerShape(12.dp))
                                .background(scheme.secondaryContainer)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                tint = scheme.onSecondaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Split Tunneling",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = scheme.onSurface,
                                fontFamily = JakartaFontFamily
                            )
                            Text(
                                text = if (settings.connectWholeDevice) {
                                    if (isConnected) "Whole device is routed through the active VPN" else "Whole-device routing selected"
                                } else if (routedAppsCount > 0) {
                                    if (isConnected) "$routedAppsCount app${if (routedAppsCount == 1) "" else "s"} routed through VPN" else "$routedAppsCount apps selected; connect to route them"
                                } else {
                                    "Select apps to route, or enable whole-device mode"
                                },
                                fontSize = 11.sp,
                                color = if (settings.connectWholeDevice || routedAppsCount > 0) scheme.primary else scheme.onSurfaceVariant,
                                fontFamily = JakartaFontFamily,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { showSplitTunnelSheet = true },
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (settings.connectWholeDevice) "Whole Device" else if (routedAppsCount > 0) "$routedAppsCount Apps" else "Select",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = JakartaFontFamily
                            )
                        }
                    }
                }
            }

            // ADVANCED SECURITY TOGGLES
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = scheme.surface),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, scheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "ROUTING & HOOKED-APP PRIVACY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = scheme.onSurfaceVariant,
                            fontFamily = JakartaFontFamily
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        VpnOptionRow(
                            title = "Connect Whole Device",
                            description = "Route device traffic through H1's root TPROXY rules",
                            checked = settings.connectWholeDevice,
                            onCheckedChange = { viewModel.toggleConnectWholeDevice(it) }
                        )

                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                        VpnOptionRow(
                            title = "Root IPv6 routing",
                            description = "Apply matching ip6tables and policy-routing rules",
                            checked = settings.rootIpv6Enabled,
                            onCheckedChange = { viewModel.setRootIpv6Enabled(it) }
                        )
                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                        VpnOptionRow(
                            title = "Hotspot clients",
                            description = "Route Wi-Fi tethering traffic through root TPROXY",
                            checked = settings.rootHotspotEnabled,
                            onCheckedChange = { viewModel.setRootHotspotEnabled(it) }
                        )
                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                        VpnOptionRow(
                            title = "USB tethering clients",
                            description = "Route RNDIS/USB tethering traffic through root TPROXY",
                            checked = settings.rootUsbEnabled,
                            onCheckedChange = { viewModel.setRootUsbEnabled(it) }
                        )
                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                        VpnOptionRow(
                            title = "Restore root tunnel after reboot",
                            description = "Start H1 Xray and restore routing after BOOT_COMPLETED",
                            checked = settings.rootAutoStart,
                            onCheckedChange = { viewModel.setRootAutoStart(it) }
                        )

                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))

                        VpnOptionRow(
                            title = "Block selected hooked apps when VPN is down",
                            description = "Best-effort app-level blocking through LSPosed; this is not Android system-wide lockdown",
                            checked = settings.vpnKillSwitchEnabled,
                            onCheckedChange = { viewModel.toggleVpnGlobalKillSwitch(it) }
                        )

                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))

                        VpnOptionRow(
                            title = "Mask VPN indicators in hooked apps",
                            description = "Hides selected VPN API results through LSPosed; Android system services may still detect the VPN",
                            checked = settings.hideVpnConnection,
                            onCheckedChange = {
                                viewModel.updateProxyingSuiteOptions(hideVpnConn = it)
                            }
                        )

                    }
                }
            }
        }
    }

    // SERVER SELECTION BOTTOM SHEET
    if (showServerSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showServerSheet = false },
            sheetState = sheetState,
            containerColor = scheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            var selectedProtoFilter by remember { mutableStateOf<VpnProtocol?>(null) }
            val filteredNodes = remember(allServers, selectedProtoFilter) {
                if (selectedProtoFilter == null) allServers
                else allServers.filter { it.protocol == selectedProtoFilter }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Select Server Node",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurface,
                        fontFamily = JakartaFontFamily
                    )
                    TextButton(onClick = {
                        showServerSheet = false
                        showImportDialog = true
                    }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Node", fontFamily = JakartaFontFamily, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Protocol Filter Pills
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedProtoFilter == null,
                        onClick = { selectedProtoFilter = null },
                        label = { Text("All Protocols", fontSize = 11.sp, fontFamily = JakartaFontFamily) },
                        shape = RoundedCornerShape(50)
                    )
                    listOf(VpnProtocol.VLESS).forEach { proto ->
                        FilterChip(
                            selected = selectedProtoFilter == proto,
                            onClick = { selectedProtoFilter = proto },
                            label = { Text(proto.badge, fontSize = 11.sp, fontFamily = JakartaFontFamily) },
                            shape = RoundedCornerShape(50)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredNodes) { server ->
                        val isCurrent = server.id == activeServer.id
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrent) scheme.primaryContainer.copy(alpha = 0.5f)
                                else scheme.surfaceContainerLow
                            ),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(
                                width = if (isCurrent) 1.5.dp else 1.dp,
                                color = if (isCurrent) scheme.primary else scheme.outlineVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    viewModel.selectActiveProxyServer(server.id)
                                    showServerSheet = false
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(scheme.primaryContainer)
                                ) {
                                    Text(
                                        text = server.protocol.badge.take(3),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = scheme.onPrimaryContainer,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = server.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = scheme.onSurface,
                                        fontFamily = JakartaFontFamily
                                    )
                                    Text(
                                        text = "${server.protocol.badge} • ${server.host}:${server.port}",
                                        fontSize = 10.sp,
                                        color = scheme.onSurfaceVariant,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (server.latencyMs > 0) "${server.latencyMs} " else "— ",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = scheme.onSurface,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = if (server.latencyMs > 0) "ms" else "",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF007AFF),
                                        fontFamily = JakartaFontFamily
                                    )
                                }
                                val isCustomServer = settings.customProxyServers.any { it.id == server.id }
                                if (isCustomServer) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    IconButton(
                                        onClick = { viewModel.deleteCustomProxyServer(server.id) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete Node",
                                            tint = scheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // PER-APP SPLIT TUNNELING MODAL SHEET
    if (showSplitTunnelSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSplitTunnelSheet = false },
            sheetState = sheetState,
            containerColor = scheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            var searchAppQuery by remember { mutableStateOf("") }
            val filteredApps = remember(allApps, searchAppQuery) {
                if (searchAppQuery.isBlank()) allApps
                else allApps.filter {
                    it.appName.contains(searchAppQuery, ignoreCase = true) ||
                            it.packageName.contains(searchAppQuery, ignoreCase = true)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = "Split Tunneling",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurface,
                            fontFamily = JakartaFontFamily
                        )
                        Text(
                            text = "$routedAppsCount apps routed through VPN",
                            fontSize = 11.sp,
                            color = scheme.primary,
                            fontFamily = JakartaFontFamily
                        )
                    }
                    Row {
                        TextButton(onClick = { viewModel.setAllAppsVpnProxy(true, userAppsOnly = true) }) {
                            Text("Route All", fontSize = 12.sp, fontFamily = JakartaFontFamily)
                        }
                        TextButton(onClick = { viewModel.setAllAppsVpnProxy(false) }) {
                            Text("Clear", fontSize = 12.sp, fontFamily = JakartaFontFamily)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = searchAppQuery,
                    onValueChange = { searchAppQuery = it },
                    placeholder = { Text("Search installed apps...", fontSize = 12.sp, fontFamily = JakartaFontFamily) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (searchAppQuery.isNotEmpty()) {
                            IconButton(onClick = { searchAppQuery = "" }) {
                                Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredApps) { appInfo ->
                        val isAppRouted = appInfo.vpnProxyEnabled
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isAppRouted) scheme.primaryContainer.copy(alpha = 0.35f)
                                    else scheme.surfaceContainerLow
                                )
                                .clickable {
                                    viewModel.toggleAppVpnProxy(appInfo, !isAppRouted)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            val iconBitmap = rememberAppIconBitmap(appInfo.packageName, appInfo.icon, 72)
                            if (iconBitmap != null) {
                                Image(
                                    bitmap = iconBitmap,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = appInfo.appName,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontFamily = JakartaFontFamily
                                )
                                Text(
                                    text = appInfo.packageName,
                                    fontSize = 10.sp,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = isAppRouted,
                                onCheckedChange = { viewModel.toggleAppVpnProxy(appInfo, it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = scheme.onPrimary,
                                    checkedTrackColor = scheme.primary
                                ),
                                modifier = Modifier.scale(0.8f)
                            )
                        }
                    }
                }
            }
        }
    }

    // IMPORT NODE URI / SOCKS5 DIALOG
    if (showImportDialog) {
        var rawUri by remember { mutableStateOf("") }
        var subscriptionUrl by remember { mutableStateOf(settings.proxySubscriptionUrl) }
        var isError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = scheme.surface,
            title = {
                Text(
                    text = "Import VPN / Proxy Node",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    fontFamily = JakartaFontFamily
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Import a VLESS TCP/RAW link using Reality or TLS. Other protocols and transports are not supported yet.",
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                        fontFamily = JakartaFontFamily
                    )
                    OutlinedTextField(
                        value = rawUri,
                        onValueChange = {
                            rawUri = it
                            isError = false
                        },
                        placeholder = { Text("vless://…", fontSize = 12.sp) },
                        isError = isError,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isError) {
                        Text(
                            text = "Unsupported or invalid VLESS link. This build supports TCP/RAW with Reality or TLS and Vision flow.",
                            color = scheme.error,
                            fontSize = 11.sp,
                            fontFamily = JakartaFontFamily
                        )
                    }
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                    OutlinedTextField(
                        value = subscriptionUrl,
                        onValueChange = { subscriptionUrl = it },
                        placeholder = { Text("https://…/subscription", fontSize = 12.sp) },
                        label = { Text("VLESS subscription URL", fontSize = 12.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            viewModel.setProxySubscriptionUrl(subscriptionUrl)
                            viewModel.refreshProxySubscription()
                            showImportDialog = false
                        },
                        enabled = subscriptionUrl.isNotBlank(),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Refresh subscription", fontFamily = JakartaFontFamily)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val success = viewModel.importProxyServerFromUri(rawUri)
                        if (success) {
                            showImportDialog = false
                        } else {
                            isError = true
                        }
                    },
                    shape = RoundedCornerShape(50)
                ) {
                    Text("Import", fontFamily = JakartaFontFamily, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel", fontFamily = JakartaFontFamily)
                }
            }
        )
    }
}

@Composable
private fun VpnOptionRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                fontFamily = JakartaFontFamily
            )
            Text(
                text = description,
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = JakartaFontFamily
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = scheme.onPrimary,
                checkedTrackColor = scheme.primary
            ),
            modifier = Modifier.scale(0.82f)
        )
    }
}
