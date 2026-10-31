package com.hollow.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hollow.app.isolation.ui.components.FloatingNavigationBar
import com.hollow.app.isolation.ui.components.HollowDynamicEmblem
import com.hollow.app.isolation.ui.components.NavItem
import com.hollow.app.isolation.ui.components.StatusBadge
import com.hollow.app.isolation.ui.navigation.Motion
import com.hollow.app.isolation.feature.apphider.AppHiderScreen
import com.hollow.app.isolation.feature.apps.AppsScreen
import com.hollow.app.isolation.feature.audit.AuditLogsScreen
import com.hollow.app.isolation.feature.dashboard.DashboardScreen
import com.hollow.app.isolation.feature.devicespoofer.DeviceSpooferScreen
import com.hollow.app.isolation.feature.files.FilesScreen
import com.hollow.app.isolation.feature.settings.SettingsScreen
import com.hollow.app.isolation.feature.settings.SettingsViewModel
import com.hollow.app.isolation.feature.terminal.MountTerminalScreen
import com.hollow.app.isolation.feature.vpn.VpnProxyScreen
import com.hollow.app.isolation.ui.viewmodel.AppViewModelProvider
import com.hollow.app.ui.theme.IsoVaultTheme
import com.hollow.app.ui.theme.JakartaFontFamily

enum class NavDestination(val label: String) {
    DASHBOARD("Home"),
    VAULT("Files"),
    APPS("Storage Isolate"),
    APP_HIDER("Hide App"),
    DEVICE_SPOOFER("Privacy"),
    AUDIT("Activity"),
    SETTINGS("Settings"),
    MOUNTS("System"),
    VPN_PROXY("V2Ray & Proxy")
}

class MainActivity : ComponentActivity() {
    companion object {
        @JvmStatic
        fun isLsposedModuleActiveReal(): Boolean {
            return false // Hooked by StorageIsolationHook on Companion and outer MainActivity
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory)
            val settings by viewModel.settings.collectAsState()
            IsoVaultTheme(
                darkTheme = settings.darkTheme,
                dynamicColor = settings.dynamicColor
            ) {
                MainAppContainer(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContainer(viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory)) {
    val scheme = MaterialTheme.colorScheme
    var currentScreen by remember { mutableStateOf(NavDestination.DASHBOARD) }
    var filesInitialTab by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val statusMessage by viewModel.statusMessage.collectAsState()
    val isLsposedActive by viewModel.isLsposedActive.collectAsState()
    val settings by viewModel.settings.collectAsState()

    LaunchedEffect(statusMessage) {
        statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    BackHandler(enabled = currentScreen != NavDestination.DASHBOARD) {
        currentScreen = NavDestination.DASHBOARD
    }

    val showMainTopBar = currentScreen == NavDestination.DASHBOARD ||
            currentScreen == NavDestination.VAULT ||
            currentScreen == NavDestination.SETTINGS ||
            currentScreen == NavDestination.MOUNTS

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = scheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (showMainTopBar) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HollowDynamicEmblem(
                                iconTheme = settings.hollowIconTheme,
                                size = 34.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Hollow",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = JakartaFontFamily,
                                color = scheme.onBackground
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.setDarkTheme(!settings.darkTheme) }
                        ) {
                            Icon(
                                imageVector = if (settings.darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "Toggle Dark Theme",
                                tint = scheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = scheme.background,
                        titleContentColor = scheme.onBackground
                    )
                )
            }
        },
        bottomBar = {
            val navItems = listOf(
                NavItem(label = "Home", icon = Icons.Filled.Home, testTag = "nav_dashboard"),
                NavItem(label = "Files", icon = Icons.Filled.Folder, testTag = "nav_vault"),
                NavItem(label = "Settings", icon = Icons.Filled.Settings, testTag = "nav_settings")
            )
            val selectedIndex = when (currentScreen) {
                NavDestination.DASHBOARD,
                NavDestination.APPS,
                NavDestination.APP_HIDER,
                NavDestination.DEVICE_SPOOFER,
                NavDestination.AUDIT,
                NavDestination.VPN_PROXY -> 0
                NavDestination.VAULT -> 1
                NavDestination.SETTINGS,
                NavDestination.MOUNTS -> 2
            }
            FloatingNavigationBar(
                items = navItems,
                selectedIndex = selectedIndex,
                onSelect = { idx ->
                    when (idx) {
                        0 -> currentScreen = NavDestination.DASHBOARD
                        1 -> {
                            filesInitialTab = 0
                            currentScreen = NavDestination.VAULT
                        }
                        2 -> currentScreen = NavDestination.SETTINGS
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    Motion.fadeThroughEnter().togetherWith(Motion.fadeThroughExit())
                },
                label = "nav_animated_content"
            ) { screen ->
                when (screen) {
                    NavDestination.DASHBOARD -> DashboardScreen(
                        onNavigateToApps = { currentScreen = NavDestination.APPS },
                        onNavigateToAppHider = { currentScreen = NavDestination.APP_HIDER },
                        onNavigateToDeviceSpoofer = { currentScreen = NavDestination.DEVICE_SPOOFER },
                        onNavigateToVault = {
                            filesInitialTab = 2
                            currentScreen = NavDestination.VAULT
                        },
                        onNavigateToFileManager = {
                            filesInitialTab = 0
                            currentScreen = NavDestination.VAULT
                        },
                        onNavigateToGallery = {
                            filesInitialTab = 1
                            currentScreen = NavDestination.VAULT
                        },
                        onNavigateToTerminal = { currentScreen = NavDestination.MOUNTS },
                        onNavigateToAudit = { currentScreen = NavDestination.AUDIT },
                        onNavigateToVpnProxy = { currentScreen = NavDestination.VPN_PROXY }
                    )
                    NavDestination.APPS -> AppsScreen(
                        onBack = { currentScreen = NavDestination.DASHBOARD }
                    )
                    NavDestination.APP_HIDER -> AppHiderScreen(
                        onBack = { currentScreen = NavDestination.DASHBOARD }
                    )
                    NavDestination.DEVICE_SPOOFER -> DeviceSpooferScreen(
                        onBack = { currentScreen = NavDestination.DASHBOARD }
                    )
                    NavDestination.VPN_PROXY -> VpnProxyScreen(
                        viewModel = viewModel,
                        onBack = { currentScreen = NavDestination.DASHBOARD }
                    )
                    NavDestination.VAULT -> FilesScreen(
                        initialTab = filesInitialTab,
                        onNavigateToApps = { currentScreen = NavDestination.APPS }
                    )
                    NavDestination.MOUNTS -> MountTerminalScreen(viewModel = viewModel)
                    NavDestination.AUDIT -> AuditLogsScreen(
                        onBack = { currentScreen = NavDestination.DASHBOARD }
                    )
                    NavDestination.SETTINGS -> SettingsScreen(
                        viewModel = viewModel,
                        onOpenDiagnostics = { currentScreen = NavDestination.MOUNTS }
                    )
                }
            }
        }
    }
}
