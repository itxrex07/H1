package com.hollow.app.isolation.feature.devicespoofer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hollow.app.isolation.model.DeviceProfilePreset
import com.hollow.app.isolation.model.IsolationRuleManager
import com.hollow.app.isolation.model.PrivacyProfile
import com.hollow.app.isolation.pkg.InstalledAppInfo
import com.hollow.app.isolation.ui.viewmodel.AppViewModelProvider
import com.hollow.app.ui.theme.JakartaFontFamily

private enum class PrivacyHubTab(val title: String) {
    APPS("Apps"),
    PROFILES("Profiles")
}

private enum class PrivacyAppFilter {
    SPOOFED_ONLY,
    USER_APPS,
    SYSTEM_APPS,
    ALL
}

@Composable
fun DeviceSpooferScreen(
    viewModel: DeviceSpooferViewModel = viewModel(factory = AppViewModelProvider.Factory),
    onBack: () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    val allApps by viewModel.allInstalledApps.collectAsState()
    val privacyProfiles by viewModel.privacyProfiles.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showAllAppsMode by remember { mutableStateOf(false) }
    var editingApp by remember { mutableStateOf<InstalledAppInfo?>(null) }
    var editingProfile by remember { mutableStateOf<PrivacyProfile?>(null) }

    BackHandler {
        when {
            editingApp != null -> editingApp = null
            showAllAppsMode -> {
                showAllAppsMode = false
                searchQuery = ""
            }
            else -> onBack()
        }
    }

    // Full-screen per-app configuration page when an app is clicked
    editingApp?.let { target ->
        ConfigurePrivacyCategorizedScreen(
            appInfo = target,
            viewModel = viewModel,
            onBack = { editingApp = null }
        )
        return
    }

    val spoofedAppsCount = remember(allApps) {
        allApps.count { it.isIdentitySpoofed }
    }

    val displayedApps = remember(allApps, searchQuery, showAllAppsMode) {
        allApps.filter { app ->
            val matchesFilter = if (showAllAppsMode) true else app.isIdentitySpoofed
            val matchesSearch = searchQuery.isBlank() ||
                    app.appName.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true) ||
                    app.spoofModel.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesSearch
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("device_spoofer_screen")
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        // Top Header
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
                    onClick = {
                        if (showAllAppsMode) {
                            showAllAppsMode = false
                            searchQuery = ""
                        } else {
                            onBack()
                        }
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("device_spoofer_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = scheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (showAllAppsMode) "Select Apps" else "Privacy",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = JakartaFontFamily
                    )
                    Text(
                        text = if (showAllAppsMode) {
                            "Tap any app to configure virtual hardware identity & shields"
                        } else {
                            "$spoofedAppsCount spoofed app${if (spoofedAppsCount == 1) "" else "s"}"
                        },
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = JakartaFontFamily
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (!showAllAppsMode) {
                Button(
                    onClick = { showAllAppsMode = true },
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("btn_add_spoof_app")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Apps",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Add Apps",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = JakartaFontFamily
                    )
                }
            } else {
                OutlinedButton(
                    onClick = {
                        showAllAppsMode = false
                        searchQuery = ""
                    },
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("btn_done_spoof_select")
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Done",
                        fontSize = 13.sp,
                        fontFamily = JakartaFontFamily,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (spoofedAppsCount > 0 || showAllAppsMode || searchQuery.isNotBlank()) {
            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = if (showAllAppsMode) "Search installed apps..." else "Search spoofed apps...",
                        color = scheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = JakartaFontFamily
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = scheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear search",
                                tint = scheme.onSurfaceVariant
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(50),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = scheme.primary,
                    unfocusedBorderColor = scheme.outlineVariant,
                    focusedContainerColor = scheme.surface,
                    unfocusedContainerColor = scheme.surface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("device_spoofer_search_input")
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (displayedApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 28.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(scheme.primaryContainer)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = null,
                            tint = scheme.onPrimaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = if (searchQuery.isNotBlank()) "No matching apps found" else "No spoofed apps configured",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurface,
                        fontFamily = JakartaFontFamily
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (searchQuery.isNotBlank()) "Try searching with a different name or package identifier." else "Tap '+ Add Apps' to choose apps and configure virtual hardware models, IMEI/Android ID, and security shields.",
                        fontSize = 13.sp,
                        color = scheme.onSurfaceVariant,
                        fontFamily = JakartaFontFamily
                    )
                    if (!showAllAppsMode) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { showAllAppsMode = true },
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.testTag("empty_state_add_spoof_app")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Select Apps to Spoof Identity",
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = JakartaFontFamily
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                items(displayedApps, key = { it.packageName }) { appInfo ->
                    val profileName = remember(appInfo.privacyProfileId, privacyProfiles) {
                        if (appInfo.privacyProfileId.isBlank()) null
                        else privacyProfiles.firstOrNull { it.id == appInfo.privacyProfileId }?.name
                    }
                    SpoofedAppCard(
                        appInfo = appInfo,
                        assignedProfileName = profileName,
                        onConfigure = { editingApp = appInfo },
                        onToggleSpoof = { enabled ->
                            if (enabled) {
                                val preset = if (appInfo.deviceProfilePreset == DeviceProfilePreset.CUSTOM) {
                                    DeviceProfilePreset.PIXEL_9_PRO
                                } else {
                                    appInfo.deviceProfilePreset
                                }
                                viewModel.saveAppIdentitySpoofConfig(
                                    appInfo = appInfo,
                                    enabled = true,
                                    preset = preset,
                                    brand = appInfo.spoofBrand.ifBlank { preset.brand },
                                    model = appInfo.spoofModel.ifBlank { preset.model },
                                    device = appInfo.spoofDevice.ifBlank { preset.device },
                                    androidId = appInfo.spoofAndroidId.ifBlank { IsolationRuleManager.generateAndroidId() },
                                    imei = appInfo.spoofImei.ifBlank { IsolationRuleManager.generateLuhnImei() },
                                    advertisingId = appInfo.spoofAdvertisingId.ifBlank { IsolationRuleManager.generateAdvertisingId() },
                                    macAddress = appInfo.spoofMacAddress.ifBlank { IsolationRuleManager.generateMacAddress() },
                                    carrierName = appInfo.spoofCarrierName.ifBlank { "T-Mobile" },
                                    simOperator = appInfo.spoofSimOperator.ifBlank { "310260" },
                                    serial = appInfo.spoofSerial.ifBlank { IsolationRuleManager.generateSerial() },
                                    hideRootAndXposed = appInfo.hideRootAndXposed,
                                    hideAdbAndDevOptions = appInfo.hideAdbAndDevOptions,
                                    hideVpnAndProxy = appInfo.hideVpnAndProxy,
                                    blockClipboardRead = appInfo.blockClipboardRead,
                                    privacyProfileId = appInfo.privacyProfileId
                                )
                            } else {
                                viewModel.removeAppIdentitySpoof(appInfo)
                            }
                        }
                    )
                }
            }
        }
    }

    // Profile Editor Dialog (Built-in & Custom Profiles)
    editingProfile?.let { profile ->
        var name by remember(profile.id) { mutableStateOf(profile.name) }
        var description by remember(profile.id) { mutableStateOf(profile.description) }
        var gpuVendor by remember(profile.id) { mutableStateOf(profile.gpuVendor) }
        var gpuRenderer by remember(profile.id) { mutableStateOf(profile.gpuRenderer) }
        var screenRes by remember(profile.id) { mutableStateOf(profile.spoofScreenResolution) }
        var spoofAndroidId by remember(profile.id) { mutableStateOf(profile.spoofAndroidId) }
        var spoofImei by remember(profile.id) { mutableStateOf(profile.spoofImei) }
        var spoofDrm by remember(profile.id) { mutableStateOf(profile.spoofDrmWidevine) }
        var spoofGpu by remember(profile.id) { mutableStateOf(profile.spoofGpuFingerprint) }
        var canvasNoise by remember(profile.id) { mutableStateOf(profile.spoofCanvasNoise) }
        var anonymizeSensors by remember(profile.id) { mutableStateOf(profile.anonymizeSensors) }
        var spoofWebRtc by remember(profile.id) { mutableStateOf(profile.spoofWebRtc) }
        var hideRoot by remember(profile.id) { mutableStateOf(profile.hideRootAndXposed) }
        var hideVpn by remember(profile.id) { mutableStateOf(profile.hideVpnAndProxy) }
        var deadman by remember(profile.id) { mutableStateOf(profile.deadmanSwitchEnabled) }

        AlertDialog(
            onDismissRequest = { editingProfile = null },
            title = {
                Text(
                    text = if (profile.isBuiltIn) "Edit Built-in Profile" else "Configure Privacy Profile",
                    fontWeight = FontWeight.Bold,
                    fontFamily = JakartaFontFamily
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Profile Name", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description", fontSize = 12.sp) },
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = gpuVendor,
                            onValueChange = { gpuVendor = it },
                            label = { Text("GPU Vendor", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = gpuRenderer,
                            onValueChange = { gpuRenderer = it },
                            label = { Text("GPU Renderer", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1.4f)
                        )
                    }
                    OutlinedTextField(
                        value = screenRes,
                        onValueChange = { screenRes = it },
                        label = { Text("Screen Resolution (e.g. 1080x2400)", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    ProfileToggleItem("Spoof Android ID & Serial", spoofAndroidId) { spoofAndroidId = it }
                    ProfileToggleItem("Spoof IMEI & Advertising ID", spoofImei) { spoofImei = it }
                    ProfileToggleItem("Spoof Widevine DRM ID", spoofDrm) { spoofDrm = it }
                    ProfileToggleItem("Spoof GPU & Display Metrics", spoofGpu) { spoofGpu = it }
                    ProfileToggleItem("Canvas & AudioContext Noise", canvasNoise) { canvasNoise = it }
                    ProfileToggleItem("Anonymize Hardware Sensors", anonymizeSensors) { anonymizeSensors = it }
                    ProfileToggleItem("Block WebRTC IP Leak", spoofWebRtc) { spoofWebRtc = it }
                    ProfileToggleItem("Hide Root, Magisk & LSPosed", hideRoot) { hideRoot = it }
                    ProfileToggleItem("Hide VPN & Proxy Status", hideVpn) { hideVpn = it }
                    ProfileToggleItem("Total Identity Leak Deadman Switch", deadman) { deadman = it }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.savePrivacyProfile(
                            profile.copy(
                                name = name.trim().ifBlank { "Custom Profile" },
                                description = description.trim(),
                                gpuVendor = gpuVendor.trim().ifBlank { "Qualcomm" },
                                gpuRenderer = gpuRenderer.trim().ifBlank { "Adreno (TM) 750" },
                                spoofScreenResolution = screenRes.trim().ifBlank { "1080x2400" },
                                spoofAndroidId = spoofAndroidId,
                                spoofSerial = spoofAndroidId,
                                spoofImei = spoofImei,
                                spoofAdvertisingId = spoofImei,
                                spoofDrmWidevine = spoofDrm,
                                spoofGpuFingerprint = spoofGpu,
                                spoofCanvasNoise = canvasNoise,
                                spoofAudioContext = canvasNoise,
                                anonymizeSensors = anonymizeSensors,
                                sensorBiasNeutralizer = anonymizeSensors,
                                spoofWebRtc = spoofWebRtc,
                                hideRootAndXposed = hideRoot,
                                hideVpnAndProxy = hideVpn,
                                deadmanSwitchEnabled = deadman
                            )
                        )
                        editingProfile = null
                    }
                ) {
                    Text("Save Profile", fontFamily = JakartaFontFamily, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingProfile = null }) {
                    Text("Cancel", fontFamily = JakartaFontFamily)
                }
            }
        )
    }
}

@Composable
private fun ProfileToggleItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontFamily = JakartaFontFamily,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun PrivacyProfileCard(
    profile: PrivacyProfile,
    appsUsingCount: Int,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .testTag("privacy_profile_card_${profile.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
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
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(scheme.primaryContainer)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = scheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = profile.name,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = scheme.onSurface,
                                fontFamily = JakartaFontFamily
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (profile.isBuiltIn) scheme.primary.copy(alpha = 0.14f)
                                        else scheme.surfaceVariant
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (profile.isBuiltIn) "Built-in" else "Custom",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (profile.isBuiltIn) scheme.primary else scheme.onSurfaceVariant,
                                    fontFamily = JakartaFontFamily
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = profile.description,
                            fontSize = 12.sp,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = JakartaFontFamily
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "GPU: ${profile.gpuRenderer} • Screen: ${profile.spoofScreenResolution} • Used by $appsUsingCount app${if (appsUsingCount == 1) "" else "s"}",
                            fontSize = 11.sp,
                            color = scheme.primary,
                            fontWeight = FontWeight.Medium,
                            fontFamily = JakartaFontFamily
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Profile",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDuplicate, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Duplicate Profile",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (onDelete != null) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Profile",
                                tint = scheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpoofedAppCard(
    appInfo: InstalledAppInfo,
    assignedProfileName: String?,
    onConfigure: () -> Unit,
    onToggleSpoof: (Boolean) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val isSpoofed = appInfo.isIdentitySpoofed
    val hasActiveShields = isSpoofed

    val displayBrand = appInfo.spoofBrand.ifBlank { appInfo.deviceProfilePreset.brand }
    val displayModel = appInfo.spoofModel.ifBlank { appInfo.deviceProfilePreset.model }

    val subtitleText = when {
        isSpoofed && !assignedProfileName.isNullOrBlank() ->
            "$displayBrand $displayModel • Profile: $assignedProfileName"
        isSpoofed ->
            "$displayBrand $displayModel • Custom Hardware & Shields"
        else ->
            "Not spoofed • Tap to configure privacy & identity"
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (hasActiveShields) scheme.primaryContainer.copy(alpha = 0.2f) else scheme.surface
        ),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            1.dp,
            if (hasActiveShields) scheme.primary.copy(alpha = 0.45f) else scheme.outlineVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onConfigure() }
            .testTag("spoofed_app_card_${appInfo.packageName}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                val iconBitmap = com.hollow.app.isolation.ui.components.rememberAppIconBitmap(
                    packageName = appInfo.packageName,
                    drawable = appInfo.icon,
                    sizePx = 96
                )
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = appInfo.appName,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(scheme.primaryContainer)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = scheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appInfo.appName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        fontFamily = JakartaFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitleText,
                        fontSize = 12.sp,
                        color = if (hasActiveShields) scheme.primary else scheme.onSurfaceVariant,
                        fontWeight = if (hasActiveShields) FontWeight.Medium else FontWeight.Normal,
                        fontFamily = JakartaFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (hasActiveShields) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(scheme.primary.copy(alpha = 0.12f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = displayModel,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = scheme.primary,
                                    fontFamily = JakartaFontFamily
                                )
                            }
                            if (appInfo.spoofLocationEnabled) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(scheme.tertiary.copy(alpha = 0.12f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Virtual GPS",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = scheme.tertiary,
                                        fontFamily = JakartaFontFamily
                                    )
                                }
                            }
                            if (appInfo.hideRootAndXposed) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(scheme.secondary.copy(alpha = 0.12f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Root Cloak",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = scheme.secondary,
                                        fontFamily = JakartaFontFamily
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = hasActiveShields,
                    onCheckedChange = { onToggleSpoof(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = scheme.onPrimary,
                        checkedTrackColor = scheme.primary
                    ),
                    modifier = Modifier.testTag("switch_spoof_${appInfo.packageName}")
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Configure",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun PrivacyMetricChip(
    label: String,
    value: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accentColor.copy(alpha = 0.10f))
            .border(1.dp, accentColor.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Column {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = JakartaFontFamily
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = JakartaFontFamily
            )
        }
    }
}
