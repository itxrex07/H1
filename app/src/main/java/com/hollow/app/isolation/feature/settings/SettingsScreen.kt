package com.hollow.app.isolation.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hollow.app.isolation.feature.apphider.HollowSelfStealthDialog
import com.hollow.app.isolation.feature.apphider.presetIconVector
import com.hollow.app.isolation.model.HollowIconTheme
import com.hollow.app.isolation.model.LauncherVisibilityMode
import com.hollow.app.isolation.ui.components.GroupedList
import com.hollow.app.isolation.ui.components.HollowDynamicEmblem
import com.hollow.app.isolation.ui.components.InfoCard
import com.hollow.app.isolation.ui.components.SectionRow
import com.hollow.app.isolation.ui.components.SettingsSectionTitle
import com.hollow.app.isolation.ui.components.SettingsSwitchRow
import com.hollow.app.isolation.ui.viewmodel.AppViewModelProvider
import com.hollow.app.ui.theme.JakartaFontFamily

private enum class SettingsSection(
    val title: String,
    val subtitle: String,
    val icon: ImageVector
) {
    STEALTH(
        title = "Disguise & Stealth",
        subtitle = "Hide launcher icon, disguise app identity & dialer triggers",
        icon = Icons.Default.VisibilityOff
    ),
    APPEARANCE(
        title = "Appearance & Theme",
        subtitle = "Dark theme, dynamic wallpaper colors & launcher icons",
        icon = Icons.Default.Palette
    ),
    STORAGE_ENGINE(
        title = "Storage Isolation Engine",
        subtitle = "Private root volumes, AES-256 rest encryption sandbox",
        icon = Icons.Default.Storage
    ),
    SYSTEM_DIAGNOSTICS(
        title = "System & Diagnostics",
        subtitle = "Root status verification, LSPosed guides & mount telemetry",
        icon = Icons.Default.Tune
    )
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
    onOpenDiagnostics: () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    val isLsposedActive by viewModel.isLsposedActive.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val rootStatus by viewModel.rootStatus.collectAsState()

    var activeSubSection by remember { mutableStateOf<SettingsSection?>(null) }
    var showSetupGuideDialog by remember { mutableStateOf(false) }
    var showHollowStealthDialog by remember { mutableStateOf(false) }

    // Sub-section back navigation
    BackHandler(enabled = activeSubSection != null) {
        activeSubSection = null
    }

    if (activeSubSection != null) {
        // Dedicated sub-section page
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .testTag("settings_sub_screen")
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                IconButton(
                    onClick = { activeSubSection = null },
                    modifier = Modifier.size(38.dp).testTag("btn_settings_back")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = scheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeSubSection?.title ?: "Settings",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        fontFamily = JakartaFontFamily,
                        color = scheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = activeSubSection?.subtitle ?: "",
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant,
                        fontFamily = JakartaFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                when (activeSubSection) {
                    SettingsSection.STEALTH -> {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = scheme.surface),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, scheme.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { showHollowStealthDialog = true }
                                    .testTag("card_hollow_self_stealth")
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
                                            imageVector = presetIconVector(settings.hollowIconPreset),
                                            contentDescription = null,
                                            tint = scheme.onPrimaryContainer,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Launcher Disguise",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = scheme.onSurface,
                                            fontFamily = JakartaFontFamily
                                        )
                                        Text(
                                            text = when (settings.hollowVisibilityMode) {
                                                LauncherVisibilityMode.VISIBLE ->
                                                    if (settings.systemUiTriggerEnabled) "Visible • SystemUI Clock trigger active"
                                                    else "Visible in launcher • Tap to disguise"
                                                LauncherVisibilityMode.DISGUISED ->
                                                    "Disguised as \"${settings.hollowCustomName}\""
                                                LauncherVisibilityMode.HIDDEN ->
                                                    "Hidden • Open via *#*#465569#*#*"
                                            },
                                            fontSize = 11.sp,
                                            color = scheme.onSurfaceVariant,
                                            fontFamily = JakartaFontFamily
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    FilledTonalButton(
                                        onClick = { showHollowStealthDialog = true },
                                        shape = RoundedCornerShape(50),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Configure",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = JakartaFontFamily
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = scheme.surface),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, scheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "Stealth Dial & System Triggers",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        fontFamily = JakartaFontFamily,
                                        color = scheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Dialer Secret Code: *#*#465569#*#* (465569 = HOLLOW)",
                                        fontSize = 12.sp,
                                        color = scheme.primary,
                                        fontFamily = JakartaFontFamily,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    ToggleRow(
                                        title = "SystemUI Clock Double-Tap",
                                        description = "Double-tap status bar clock to launch Hollow when hidden",
                                        checked = settings.systemUiTriggerEnabled,
                                        enabled = true,
                                        onChange = {
                                            viewModel.updateHollowStealthConfig(
                                                visibilityMode = settings.hollowVisibilityMode,
                                                customName = settings.hollowCustomName,
                                                iconPreset = settings.hollowIconPreset,
                                                systemUiTriggerEnabled = it,
                                                customIconBase64 = settings.hollowCustomIconBase64,
                                                customIconDonorPkg = settings.hollowCustomIconDonorPkg,
                                                spoofInSettings = settings.hollowSpoofInSettings
                                            )
                                        }
                                    )
                                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                                    ToggleRow(
                                        title = "Disguise in System Settings",
                                        description = "Spoof name and icon inside Android App Settings",
                                        checked = settings.hollowSpoofInSettings,
                                        enabled = true,
                                        onChange = {
                                            viewModel.updateHollowStealthConfig(
                                                visibilityMode = settings.hollowVisibilityMode,
                                                customName = settings.hollowCustomName,
                                                iconPreset = settings.hollowIconPreset,
                                                systemUiTriggerEnabled = settings.systemUiTriggerEnabled,
                                                customIconBase64 = settings.hollowCustomIconBase64,
                                                customIconDonorPkg = settings.hollowCustomIconDonorPkg,
                                                spoofInSettings = it
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    SettingsSection.APPEARANCE -> {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = scheme.surface),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, scheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "Theme & Dynamics",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        fontFamily = JakartaFontFamily,
                                        color = scheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    ToggleRow(
                                        title = "Dark theme",
                                        description = "Deep dark AMOLED aesthetic",
                                        checked = settings.darkTheme,
                                        enabled = true,
                                        onChange = { viewModel.setDarkTheme(it) }
                                    )
                                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                                    ToggleRow(
                                        title = "Dynamic wallpaper colors (Material You)",
                                        description = "Harmonize with system wallpaper on Android 12+",
                                        checked = settings.dynamicColor,
                                        enabled = true,
                                        onChange = { viewModel.setDynamicColor(it) }
                                    )
                                }
                            }
                        }

                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = scheme.surface),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, scheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "Launcher Icon Preset",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = scheme.onSurface,
                                        fontFamily = JakartaFontFamily
                                    )
                                    Text(
                                        text = "Select custom launcher emblem style",
                                        fontSize = 11.sp,
                                        color = scheme.onSurfaceVariant,
                                        fontFamily = JakartaFontFamily
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        HollowIconTheme.entries.forEach { themeOption ->
                                            val isSelected = settings.hollowIconTheme == themeOption
                                            Card(
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (isSelected) {
                                                        scheme.primaryContainer.copy(alpha = 0.45f)
                                                    } else {
                                                        scheme.surfaceContainerLow
                                                    }
                                                ),
                                                shape = RoundedCornerShape(14.dp),
                                                border = BorderStroke(
                                                    width = if (isSelected) 1.5.dp else 1.dp,
                                                    color = if (isSelected) scheme.primary else scheme.outlineVariant
                                                ),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable { viewModel.setHollowIconTheme(themeOption) }
                                                    .testTag("icon_theme_${themeOption.name.lowercase()}")
                                            ) {
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 12.dp, horizontal = 4.dp)
                                                ) {
                                                    HollowDynamicEmblem(
                                                        iconTheme = themeOption,
                                                        size = 36.dp
                                                    )
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Text(
                                                        text = themeOption.label,
                                                        fontSize = 11.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (isSelected) scheme.primary else scheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        fontFamily = JakartaFontFamily
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    SettingsSection.STORAGE_ENGINE -> {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = scheme.surface),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, scheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "Root Storage Sandboxing",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        fontFamily = JakartaFontFamily,
                                        color = scheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    ToggleRow(
                                        title = "Private volumes (Root)",
                                        description = "Mount a dedicated storage sandbox per app when rooted",
                                        checked = settings.rootVolumesEnabled,
                                        enabled = true,
                                        onChange = { viewModel.setRootVolumesEnabled(it) }
                                    )
                                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                                    ToggleRow(
                                        title = "Lock volumes when unmounted",
                                        description = "Automatically encrypt app volumes with AES-256 when closed",
                                        checked = settings.encryptVolumesAtRest,
                                        enabled = settings.rootVolumesEnabled,
                                        onChange = { viewModel.setEncryptVolumesAtRest(it) }
                                    )
                                }
                            }
                        }
                    }

                    SettingsSection.SYSTEM_DIAGNOSTICS -> {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = scheme.surface),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, scheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(scheme.secondaryContainer)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.HelpOutline,
                                                    contentDescription = null,
                                                    tint = scheme.onSecondaryContainer,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "LSPosed Hook Activation",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = scheme.onSurface,
                                                    fontFamily = JakartaFontFamily
                                                )
                                                Text(
                                                    text = if (isLsposedActive) "Module active in LSPosed" else "Module not hooked yet",
                                                    fontSize = 11.sp,
                                                    color = if (isLsposedActive) scheme.primary else scheme.onSurfaceVariant,
                                                    fontFamily = JakartaFontFamily
                                                )
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = { showSetupGuideDialog = true },
                                            shape = RoundedCornerShape(50),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            modifier = Modifier.testTag("btn_open_setup_guide")
                                        ) {
                                            Text(
                                                text = "Guide",
                                                fontSize = 12.sp,
                                                fontFamily = JakartaFontFamily,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))
                                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Superuser (Root) Check",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = scheme.onSurface,
                                                fontFamily = JakartaFontFamily
                                            )
                                            Text(
                                                text = rootStatus,
                                                fontSize = 11.sp,
                                                color = scheme.onSurfaceVariant,
                                                fontFamily = JakartaFontFamily
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.checkRoot() },
                                            shape = RoundedCornerShape(50),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = "Check",
                                                fontSize = 12.sp,
                                                fontFamily = JakartaFontFamily,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))
                                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onOpenDiagnostics() }
                                            .padding(vertical = 4.dp)
                                            .testTag("nav_mounts")
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Tune,
                                                contentDescription = null,
                                                tint = scheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = "Mount Terminal & Live Telemetry",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = scheme.onSurface,
                                                fontFamily = JakartaFontFamily
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = scheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    null -> {}
                }
            }
        }
    } else {
        // Main Settings Screen: Card-based Hub (No flat clutter)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .testTag("settings_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Settings",
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    fontFamily = JakartaFontFamily,
                    color = scheme.onBackground
                )
                Text(
                    text = "Tap any card to view and configure options",
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                    fontFamily = JakartaFontFamily
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Section Rows grouped seamlessly in M3 Expressive GroupedList
            item {
                GroupedList(horizontalPadding = 0.dp) {
                    SettingsSection.entries.forEach { section ->
                        val dynamicStatus = when (section) {
                            SettingsSection.STEALTH -> {
                                when (settings.hollowVisibilityMode) {
                                    LauncherVisibilityMode.VISIBLE -> "Visible in launcher"
                                    LauncherVisibilityMode.DISGUISED -> "Disguised as ${settings.hollowCustomName}"
                                    LauncherVisibilityMode.HIDDEN -> "Hidden • *#*#465569#*#*"
                                }
                            }
                            SettingsSection.APPEARANCE -> {
                                val themeMode = if (settings.darkTheme) "Dark" else "Light"
                                val dyn = if (settings.dynamicColor) "Material You" else "Default"
                                "$themeMode • $dyn"
                            }
                            SettingsSection.STORAGE_ENGINE -> {
                                if (settings.rootVolumesEnabled) "Private Volumes Active • AES-256"
                                else "Standard Sandbox Engine"
                            }
                            SettingsSection.SYSTEM_DIAGNOSTICS -> {
                                "Root: $rootStatus • LSPosed: ${if (isLsposedActive) "Active" else "Inactive"}"
                            }
                        }
                        add {
                            SectionRow(
                                icon = section.icon,
                                title = section.title,
                                subtitle = "$dynamicStatus • ${section.subtitle}",
                                onClick = { activeSubSection = section },
                                testTag = "settings_card_${section.name.lowercase()}"
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showHollowStealthDialog) {
        HollowSelfStealthDialog(
            currentMode = settings.hollowVisibilityMode,
            currentCustomName = settings.hollowCustomName,
            currentIconPreset = settings.hollowIconPreset,
            currentSystemUiTrigger = settings.systemUiTriggerEnabled,
            currentCustomIconBase64 = settings.hollowCustomIconBase64,
            currentCustomIconDonorPkg = settings.hollowCustomIconDonorPkg,
            currentSpoofInSettings = settings.hollowSpoofInSettings,
            onDecodeImageUri = { uri, cb -> viewModel.decodeImageUriToBase64(uri, cb) },
            onDismiss = { showHollowStealthDialog = false },
            onSave = { mode, customName, iconPreset, sysUiTrigger, customIconB64, donorPkg, spoofSettings ->
                viewModel.updateHollowStealthConfig(
                    visibilityMode = mode,
                    customName = customName,
                    iconPreset = iconPreset,
                    systemUiTriggerEnabled = sysUiTrigger,
                    customIconBase64 = customIconB64,
                    customIconDonorPkg = donorPkg,
                    spoofInSettings = spoofSettings
                )
                showHollowStealthDialog = false
            }
        )
    }

    if (showSetupGuideDialog) {
        AlertDialog(
            onDismissRequest = { showSetupGuideDialog = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = scheme.surface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(scheme.primaryContainer)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = scheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Protection Setup Guide",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        fontFamily = JakartaFontFamily,
                        color = scheme.onSurface
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SetupStep(
                        step = "1",
                        title = "Enable Hollow in LSPosed / Vector",
                        desc = "Open your Xposed manager, enable the Hollow module, and select target apps in its scope."
                    )
                    SetupStep(
                        step = "2",
                        title = "Add apps in Storage Isolate",
                        desc = "Open 'Storage Isolate' on the Home screen, tap '+ Add Apps', and switch on any app you want isolated."
                    )
                    SetupStep(
                        step = "3",
                        title = "Force-stop & reopen the app",
                        desc = "The app will now run inside its own private storage sandbox."
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showSetupGuideDialog = false },
                    shape = RoundedCornerShape(50)
                ) {
                    Text("Got it", fontFamily = JakartaFontFamily, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSetupGuideDialog = false }) {
                    Text("Close", fontFamily = JakartaFontFamily)
                }
            }
        )
    }
}

@Composable
fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
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
            onCheckedChange = onChange,
            enabled = enabled
        )
    }
}

@Composable
fun SetupStep(step: String, title: String, desc: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(scheme.primaryContainer)
        ) {
            Text(
                text = step,
                color = scheme.onPrimaryContainer,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = JakartaFontFamily
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                fontFamily = JakartaFontFamily
            )
            Text(
                text = desc,
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant,
                fontFamily = JakartaFontFamily
            )
        }
    }
}
