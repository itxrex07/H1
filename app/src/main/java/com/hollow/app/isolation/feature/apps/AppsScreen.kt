package com.hollow.app.isolation.feature.apps

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hollow.app.isolation.model.IsolationMode
import com.hollow.app.isolation.pkg.InstalledAppInfo
import com.hollow.app.isolation.ui.components.ModeBadge
import com.hollow.app.isolation.ui.components.StatusBadge
import com.hollow.app.isolation.ui.viewmodel.AppViewModelProvider
import com.hollow.app.ui.theme.JakartaFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    viewModel: AppIsolationViewModel = viewModel(factory = AppViewModelProvider.Factory),
    onBack: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val filteredApps by viewModel.filteredApps.collectAsState()
    val allInstalledApps by viewModel.allInstalledApps.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val activeFilter by viewModel.filter.collectAsState()
    val selectedApp by viewModel.selectedAppForDetail.collectAsState()
    val configs by viewModel.configs.collectAsState()

    val isSelectAppsMode = activeFilter != AppListFilter.ISOLATED_ONLY

    BackHandler(enabled = selectedApp != null || isSelectAppsMode || onBack != null) {
        when {
            selectedApp != null -> viewModel.selectAppForDetail(null)
            isSelectAppsMode -> {
                viewModel.setFilter(AppListFilter.ISOLATED_ONLY)
                viewModel.setSearchQuery("")
            }
            onBack != null -> onBack()
        }
    }

    // Default to Storage Isolate (ISOLATED_ONLY) whenever opening this screen
    LaunchedEffect(Unit) {
        viewModel.setFilter(AppListFilter.ISOLATED_ONLY)
    }

    val detailSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val protectedAppsCount = remember(allInstalledApps) {
        allInstalledApps.count { it.isIsolated }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("apps_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (onBack != null || isSelectAppsMode) {
                        IconButton(
                            onClick = {
                                if (isSelectAppsMode) {
                                    viewModel.setFilter(AppListFilter.ISOLATED_ONLY)
                                    viewModel.setSearchQuery("")
                                } else {
                                    onBack?.invoke()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = scheme.onBackground
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isSelectAppsMode) "Select Apps" else "Storage Isolate",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = JakartaFontFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = scheme.onBackground
                        )
                        Text(
                            text = if (isSelectAppsMode) {
                                "Switch on any app to isolate its storage and gallery access"
                            } else {
                                "$protectedAppsCount app${if (protectedAppsCount == 1) "" else "s"} isolated from your personal storage"
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

                if (!isSelectAppsMode) {
                    Button(
                        onClick = { viewModel.setFilter(AppListFilter.USER_APPS) },
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("btn_add_apps_top")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
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
                            viewModel.setFilter(AppListFilter.ISOLATED_ONLY)
                            viewModel.setSearchQuery("")
                        },
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("btn_done_isolate_select")
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
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = JakartaFontFamily
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = {
                    Text(
                        if (isSelectAppsMode) "Search installed apps..." else "Search isolated apps...",
                        color = scheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontFamily = JakartaFontFamily
                    )
                },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = scheme.onSurfaceVariant)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Clear", tint = scheme.onSurfaceVariant)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("apps_search_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            val filterChipColors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = scheme.primaryContainer,
                selectedLabelColor = scheme.onPrimaryContainer,
                containerColor = scheme.surface,
                labelColor = scheme.onSurfaceVariant
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    FilterChip(
                        selected = activeFilter == AppListFilter.ISOLATED_ONLY,
                        onClick = { viewModel.setFilter(AppListFilter.ISOLATED_ONLY) },
                        label = { Text("Isolated ($protectedAppsCount)", fontFamily = JakartaFontFamily) },
                        shape = RoundedCornerShape(50),
                        colors = filterChipColors
                    )
                }
                item {
                    FilterChip(
                        selected = activeFilter == AppListFilter.USER_APPS,
                        onClick = { viewModel.setFilter(AppListFilter.USER_APPS) },
                        label = { Text("Downloaded", fontFamily = JakartaFontFamily) },
                        shape = RoundedCornerShape(50),
                        colors = filterChipColors
                    )
                }
                item {
                    FilterChip(
                        selected = activeFilter == AppListFilter.SYSTEM_APPS,
                        onClick = { viewModel.setFilter(AppListFilter.SYSTEM_APPS) },
                        label = { Text("System", fontFamily = JakartaFontFamily) },
                        shape = RoundedCornerShape(50),
                        colors = filterChipColors
                    )
                }
                item {
                    FilterChip(
                        selected = activeFilter == AppListFilter.ALL,
                        onClick = { viewModel.setFilter(AppListFilter.ALL) },
                        label = { Text("All (${allInstalledApps.size})", fontFamily = JakartaFontFamily) },
                        shape = RoundedCornerShape(50),
                        colors = filterChipColors
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .background(scheme.primaryContainer)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = scheme.onPrimaryContainer,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = if (activeFilter == AppListFilter.ISOLATED_ONLY) {
                                "No isolated apps yet"
                            } else {
                                "No matching apps found"
                            },
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = JakartaFontFamily,
                            color = scheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap '+ Add Apps' to choose apps you want isolated in their own private sandbox.",
                            fontSize = 13.sp,
                            color = scheme.onSurfaceVariant,
                            fontFamily = JakartaFontFamily
                        )
                        if (!isSelectAppsMode) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { viewModel.setFilter(AppListFilter.USER_APPS) },
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.testTag("btn_add_apps_empty")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Add Apps to Isolate",
                                    fontFamily = JakartaFontFamily,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 28.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(filteredApps, key = { it.packageName }) { appInfo ->
                        AppItemCard(
                            appInfo = appInfo,
                            onToggleIsolation = { enable -> viewModel.toggleAppIsolation(appInfo, enable) },
                            onClick = { viewModel.selectAppForDetail(appInfo) }
                        )
                    }
                }
            }
        }
    }

    selectedApp?.let { appInfo ->
        val cfg = configs.find { it.packageName == appInfo.packageName }

        ModalBottomSheet(
            onDismissRequest = { viewModel.selectAppForDetail(null) },
            sheetState = detailSheetState,
            containerColor = scheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            AppIsolationDetailContent(
                appInfo = appInfo,
                blockRootPollution = cfg?.blockRootPollution ?: true,
                encryptMediaOnly = cfg?.encryptMediaOnly ?: false,
                whitelistedFolders = cfg?.whitelistedFolders ?: "",
                onUpdateMode = { mode -> viewModel.updateAppIsolationMode(appInfo.packageName, mode) },
                onTogglePollution = { block -> viewModel.updateRootPollution(appInfo.packageName, block) },
                onToggleEncryptMediaOnly = { redirect -> viewModel.updateEncryptMediaOnly(appInfo.packageName, redirect) },
                onUpdateWhitelist = { whitelist -> viewModel.updateWhitelistedFolders(appInfo.packageName, whitelist) },
                onAttachVolume = { viewModel.attachVolume(appInfo.packageName) },
                onDetachVolume = { viewModel.detachVolume(appInfo.packageName) },
                onPurgeVault = { viewModel.purgeVaultForPackage(appInfo.packageName) }
            )
        }
    }
}

@Composable
fun AppItemCard(
    appInfo: InstalledAppInfo,
    onToggleIsolation: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (appInfo.isIsolated) scheme.primaryContainer.copy(alpha = 0.25f) else scheme.surface
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            if (appInfo.isIsolated) scheme.primary.copy(alpha = 0.4f) else scheme.outlineVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("app_item_${appInfo.packageName.replace('.', '_')}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp)
        ) {
            val drawable = appInfo.icon
            if (drawable is BitmapDrawable) {
                Image(
                    bitmap = drawable.bitmap.asImageBitmap(),
                    contentDescription = appInfo.appName,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(scheme.primaryContainer)
                ) {
                    Text(
                        text = appInfo.appName.take(1).uppercase(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = JakartaFontFamily,
                        color = scheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appInfo.appName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = JakartaFontFamily
                )

                Spacer(modifier = Modifier.height(2.dp))

                if (appInfo.isIsolated) {
                    ModeBadge(mode = appInfo.isolationMode)
                } else {
                    Text(
                        text = "Not protected • Tap to configure",
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = JakartaFontFamily
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Switch(
                checked = appInfo.isIsolated,
                onCheckedChange = { onToggleIsolation(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = scheme.onPrimary,
                    checkedTrackColor = scheme.primary
                ),
                modifier = Modifier.testTag("switch_isolate_${appInfo.packageName.replace('.', '_')}")
            )
        }
    }
}

@Composable
fun AppIsolationDetailContent(
    appInfo: InstalledAppInfo,
    blockRootPollution: Boolean,
    encryptMediaOnly: Boolean,
    whitelistedFolders: String,
    onUpdateMode: (IsolationMode) -> Unit,
    onTogglePollution: (Boolean) -> Unit,
    onToggleEncryptMediaOnly: (Boolean) -> Unit,
    onUpdateWhitelist: (String) -> Unit,
    onAttachVolume: () -> Unit,
    onDetachVolume: () -> Unit,
    onPurgeVault: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var whitelistText by remember { mutableStateOf(whitelistedFolders) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .padding(bottom = 28.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appInfo.appName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = JakartaFontFamily,
                    color = scheme.onSurface
                )
                Text(
                    text = appInfo.packageName,
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                    fontFamily = JakartaFontFamily
                )
            }
            StatusBadge(
                text = if (appInfo.isIsolated) "Protected" else "Off",
                color = if (appInfo.isIsolated) scheme.primary else scheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = scheme.outlineVariant)
        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "How should Hollow protect this app?",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = JakartaFontFamily,
            color = scheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        IsolationMode.values().forEach { mode ->
            val isSelected = appInfo.isolationMode == mode
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) scheme.primaryContainer.copy(alpha = 0.4f) else scheme.surfaceContainerLow
                ),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    1.dp,
                    if (isSelected) scheme.primary else scheme.outlineVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onUpdateMode(mode) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(12.dp)
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onUpdateMode(mode) }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mode.title,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = scheme.onSurface,
                            fontFamily = JakartaFontFamily
                        )
                        Text(
                            text = mode.description,
                            fontSize = 12.sp,
                            color = scheme.onSurfaceVariant,
                            fontFamily = JakartaFontFamily
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Storage Clutter Block
        Card(
            colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Keep phone storage clean",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = scheme.onSurface,
                        fontFamily = JakartaFontFamily
                    )
                    Text(
                        text = "Stop this app from creating clutter folders on your phone",
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                        fontFamily = JakartaFontFamily
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = blockRootPollution,
                    onCheckedChange = { onTogglePollution(it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Per-App System Photo & File Picker Sandbox
        Card(
            colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "System Photo & File Picker",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = scheme.onSurface,
                        fontFamily = JakartaFontFamily
                    )
                    Text(
                        text = if (encryptMediaOnly)
                            "Selected media is copied directly to this app's private sandbox"
                        else
                            "Standard access to system media files",
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                        fontFamily = JakartaFontFamily
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = encryptMediaOnly,
                    onCheckedChange = { onToggleEncryptMediaOnly(it) },
                    modifier = Modifier.testTag("switch_picker_sandbox")
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = whitelistText,
            onValueChange = {
                whitelistText = it
                onUpdateWhitelist(it)
            },
            label = { Text("Allowed shared folders (optional)", fontSize = 12.sp) },
            placeholder = { Text("e.g. Download, Music", fontSize = 13.sp) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onAttachVolume,
                shape = RoundedCornerShape(50),
                modifier = Modifier.weight(1f)
            ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Mount", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onDetachVolume,
                shape = RoundedCornerShape(50),
                modifier = Modifier.weight(1f)
            ) {
                Text("Unmount", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
                onClick = onPurgeVault,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error),
                border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(50),
                modifier = Modifier.weight(1f)
            ) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clear", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
