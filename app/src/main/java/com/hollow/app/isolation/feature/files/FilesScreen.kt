package com.hollow.app.isolation.feature.files

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hollow.app.isolation.feature.gallery.GalleryScreen
import com.hollow.app.isolation.feature.vault.VaultScreen
import com.hollow.app.isolation.feature.vault.VaultViewModel
import com.hollow.app.isolation.ui.viewmodel.AppViewModelProvider
import com.hollow.app.isolation.util.formatBytes
import com.hollow.app.ui.theme.JakartaFontFamily
import java.io.File
import java.text.DateFormat
import java.util.Date

private enum class GoogleFilesSubView {
    BROWSE_HOME,
    FOLDER_EXPLORER,
    GALLERY,
    SAFE_FOLDER
}

/**
 * Clean, Google Files-inspired Browse & Storage Hub.
 */
@Composable
fun FilesScreen(
    viewModel: VaultViewModel = viewModel(factory = AppViewModelProvider.Factory),
    fmViewModel: FileManagerViewModel = viewModel(factory = AppViewModelProvider.Factory),
    initialTab: Int = 0,
    onNavigateToApps: () -> Unit = {}
) {
    var subView by remember {
        mutableStateOf(
            when (initialTab) {
                1 -> GoogleFilesSubView.GALLERY
                2 -> GoogleFilesSubView.SAFE_FOLDER
                else -> GoogleFilesSubView.BROWSE_HOME
            }
        )
    }
    var galleryInitialMode by remember { mutableStateOf("PHOTOS") }

    LaunchedEffect(initialTab) {
        subView = when (initialTab) {
            1 -> {
                galleryInitialMode = "PHOTOS"
                GoogleFilesSubView.GALLERY
            }
            2 -> GoogleFilesSubView.SAFE_FOLDER
            else -> GoogleFilesSubView.BROWSE_HOME
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("files_screen")
    ) {
        when (subView) {
            GoogleFilesSubView.BROWSE_HOME -> {
                GoogleFilesBrowseHome(
                    isoViewModel = viewModel,
                    fmViewModel = fmViewModel,
                    onOpenCategoryGallery = { mode ->
                        galleryInitialMode = mode
                        subView = GoogleFilesSubView.GALLERY
                    },
                    onOpenSafeFolder = {
                        subView = GoogleFilesSubView.SAFE_FOLDER
                    },
                    onOpenFolder = { rootType, subDirName ->
                        fmViewModel.switchStorageRoot(rootType)
                        if (subDirName != null) {
                            val base = File(fmViewModel.uiState.value.rootPath)
                            val target = File(base, subDirName).apply { mkdirs() }
                            fmViewModel.navigateToPath(target.absolutePath)
                        }
                        subView = GoogleFilesSubView.FOLDER_EXPLORER
                    },
                    onOpenSeparateDrive = { pkg, name ->
                        fmViewModel.openSeparateAppDrive(pkg, name)
                        subView = GoogleFilesSubView.FOLDER_EXPLORER
                    },
                    onOpenApps = onNavigateToApps
                )
            }
            GoogleFilesSubView.FOLDER_EXPLORER -> {
                CleanFolderExplorerView(
                    fmViewModel = fmViewModel,
                    onBackToBrowseHome = {
                        fmViewModel.setSearchQuery("")
                        subView = GoogleFilesSubView.BROWSE_HOME
                    }
                )
            }
            GoogleFilesSubView.GALLERY -> {
                GalleryScreen(
                    initialMode = galleryInitialMode,
                    onBack = { subView = GoogleFilesSubView.BROWSE_HOME }
                )
            }
            GoogleFilesSubView.SAFE_FOLDER -> {
                VaultScreen(
                    viewModel = viewModel,
                    onBack = { subView = GoogleFilesSubView.BROWSE_HOME }
                )
            }
        }
    }
}

@Composable
private fun GoogleFilesBrowseHome(
    isoViewModel: VaultViewModel,
    fmViewModel: FileManagerViewModel,
    onOpenCategoryGallery: (String) -> Unit,
    onOpenSafeFolder: () -> Unit,
    onOpenFolder: (StorageRootType, String?) -> Unit,
    onOpenSeparateDrive: (packageName: String, appName: String) -> Unit = { _, _ -> },
    onOpenApps: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val uiState by fmViewModel.uiState.collectAsState()
    val vaultFiles by isoViewModel.vaultFiles.collectAsState()
    val totalEncryptedBytes by isoViewModel.totalEncryptedBytes.collectAsState()
    val isolatedCount by isoViewModel.isolatedCount.collectAsState()
    val notice by fmViewModel.notice.collectAsState()
    val breakdown = uiState.storageBreakdown

    var searchQuery by remember { mutableStateOf("") }
    var showHomeNewMenu by remember { mutableStateOf(false) }

    val homeImportFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            fmViewModel.switchStorageRoot(StorageRootType.WORKSPACE)
            fmViewModel.importExternalUrisHere(uris)
        }
    }

    val homeImportFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            fmViewModel.switchStorageRoot(StorageRootType.WORKSPACE)
            fmViewModel.importExternalFolderHere(treeUri)
            onOpenFolder(StorageRootType.WORKSPACE, null)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 6.dp, bottom = 88.dp)
        ) {
            if (notice != null) {
                item {
                    Surface(
                        color = scheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { fmViewModel.clearNotice() }
                    ) {
                        Text(
                            text = notice ?: "",
                            fontSize = 12.sp,
                            color = scheme.onPrimaryContainer,
                            fontFamily = JakartaFontFamily,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        // 1. Google Files Pill Search Bar
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { query ->
                    searchQuery = query
                    if (query.isNotBlank()) {
                        fmViewModel.switchStorageRoot(StorageRootType.WORKSPACE)
                        fmViewModel.setSearchQuery(query)
                        onOpenFolder(StorageRootType.WORKSPACE, null)
                    }
                },
                placeholder = {
                    Text(
                        text = "Search files and folders",
                        fontSize = 14.sp,
                        color = scheme.onSurfaceVariant,
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
                singleLine = true,
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 2. Categories Section (Google Files 2x3 Grid)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Categories",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                    fontFamily = JakartaFontFamily
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    GoogleFilesCategoryTile(
                        title = "Downloads",
                        subtitle = "Saved files",
                        icon = Icons.Default.Download,
                        iconTint = Color(0xFF0B57D0),
                        onClick = { onOpenFolder(StorageRootType.WORKSPACE, "Downloads") },
                        modifier = Modifier.weight(1f)
                    )
                    GoogleFilesCategoryTile(
                        title = "Images",
                        subtitle = formatBytes(breakdown.imagesBytes),
                        icon = Icons.Default.Image,
                        iconTint = Color(0xFFD93025),
                        onClick = { onOpenCategoryGallery("PHOTOS") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    GoogleFilesCategoryTile(
                        title = "Videos",
                        subtitle = formatBytes(breakdown.videosBytes),
                        icon = Icons.Default.VideoFile,
                        iconTint = Color(0xFF188038),
                        onClick = { onOpenCategoryGallery("VIDEOS") },
                        modifier = Modifier.weight(1f)
                    )
                    GoogleFilesCategoryTile(
                        title = "Audio",
                        subtitle = formatBytes(breakdown.audioBytes),
                        icon = Icons.Default.AudioFile,
                        iconTint = Color(0xFFE37400),
                        onClick = { onOpenCategoryGallery("AUDIO") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    GoogleFilesCategoryTile(
                        title = "Documents",
                        subtitle = formatBytes(breakdown.docsAndCodeBytes),
                        icon = Icons.Default.Description,
                        iconTint = Color(0xFF7627BB),
                        onClick = { onOpenFolder(StorageRootType.WORKSPACE, "Documents") },
                        modifier = Modifier.weight(1f)
                    )
                    GoogleFilesCategoryTile(
                        title = "Apps",
                        subtitle = "$isolatedCount protected",
                        icon = Icons.Default.Apps,
                        iconTint = scheme.primary,
                        onClick = onOpenApps,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 3. Collections Section (Safe Folder & Starred — just like Google Files!)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Collections",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                    fontFamily = JakartaFontFamily
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    GoogleFilesCollectionCard(
                        title = "Safe folder",
                        subtitle = "${vaultFiles.size} items • ${formatBytes(totalEncryptedBytes)}",
                        icon = Icons.Default.Lock,
                        accent = scheme.primary,
                        onClick = onOpenSafeFolder,
                        modifier = Modifier.weight(1f)
                    )
                    GoogleFilesCollectionCard(
                        title = "Starred",
                        subtitle = "Favorites",
                        icon = Icons.Default.Star,
                        accent = Color(0xFFE37400),
                        onClick = { onOpenCategoryGallery("STARRED") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 4. Storage Devices: Internal Storage (Hollow Drive) & External Storage (Assigned App Drives)
        item {
            val allApps by isoViewModel.allInstalledApps.collectAsState()
            val separateDriveApps = allApps.filter {
                it.isIsolated && it.isolationMode != com.hollow.app.isolation.model.IsolationMode.SHARED_WORKSPACE
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Storage devices",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                    fontFamily = JakartaFontFamily
                )

                val workspaceUsedBytes = breakdown.imagesBytes +
                        breakdown.videosBytes +
                        breakdown.audioBytes +
                        breakdown.docsAndCodeBytes +
                        breakdown.archivesBytes

                // Main Internal Storage: Hollow Drive
                GoogleFilesStorageDeviceCard(
                    title = "Hollow Drive (Internal Storage)",
                    subtitle = "${formatBytes(workspaceUsedBytes)} • Zero-exposure private storage (Hidden from Google Files)",
                    icon = Icons.Default.Folder,
                    progressFraction = 0.28f,
                    onClick = { onOpenFolder(StorageRootType.WORKSPACE, null) }
                )

                // Assigned App Drives (Appearing like SD Cards / External Storage below Hollow Drive)
                if (separateDriveApps.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Assigned drives (External storage)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurfaceVariant,
                        fontFamily = JakartaFontFamily
                    )
                    separateDriveApps.forEach { app ->
                        GoogleFilesStorageDeviceCard(
                            title = "${app.appName} Drive",
                            subtitle = "Dedicated isolated storage volume • Tap to explore",
                            icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                            progressFraction = 0.15f,
                            onClick = { onOpenSeparateDrive(app.packageName, app.appName) }
                        )
                    }
                } else {
                    Surface(
                        color = scheme.surfaceContainerLow,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "All apps use unified Hollow Drive by default. Per-app assigned drives appear here like SD cards.",
                            fontSize = 12.sp,
                            color = scheme.onSurfaceVariant,
                            fontFamily = JakartaFontFamily,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
        }

        // "+ New" button on Browse Home for quick file/folder import into Hollow Workspace
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            ExtendedFloatingActionButton(
                onClick = { showHomeNewMenu = true },
                containerColor = scheme.primary,
                contentColor = scheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New", fontFamily = JakartaFontFamily, fontWeight = FontWeight.SemiBold) }
            )

            DropdownMenu(
                expanded = showHomeNewMenu,
                onDismissRequest = { showHomeNewMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Import folder", fontFamily = JakartaFontFamily) },
                    leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                    onClick = {
                        showHomeNewMenu = false
                        homeImportFolderLauncher.launch(null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Import files", fontFamily = JakartaFontFamily) },
                    leadingIcon = { Icon(Icons.Default.UploadFile, contentDescription = null) },
                    onClick = {
                        showHomeNewMenu = false
                        homeImportFilesLauncher.launch(arrayOf("*/*"))
                    }
                )
                DropdownMenuItem(
                    text = { Text("Open Hollow workspace", fontFamily = JakartaFontFamily) },
                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    onClick = {
                        showHomeNewMenu = false
                        onOpenFolder(StorageRootType.WORKSPACE, null)
                    }
                )
            }
        }
    }
}

@Composable
private fun GoogleFilesCategoryTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier.clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.12f))
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = JakartaFontFamily
                )
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
}

@Composable
private fun GoogleFilesCollectionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier.clickable { onClick() }
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
                    .background(accent.copy(alpha = 0.14f))
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = JakartaFontFamily
                )
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
}

@Composable
private fun GoogleFilesStorageDeviceCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    progressFraction: Float,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(scheme.primaryContainer)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = scheme.onPrimaryContainer,
                    modifier = Modifier.size(21.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    fontFamily = JakartaFontFamily
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(scheme.surfaceContainerHigh)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressFraction)
                            .clip(RoundedCornerShape(50))
                            .background(scheme.primary)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                    fontFamily = JakartaFontFamily
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = scheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CleanFolderExplorerView(
    fmViewModel: FileManagerViewModel,
    onBackToBrowseHome: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val uiState by fmViewModel.uiState.collectAsState()
    val notice by fmViewModel.notice.collectAsState()

    var showSearchBar by remember { mutableStateOf(uiState.searchQuery.isNotBlank()) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showCreateMenu by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewNoteDialog by remember { mutableStateOf(false) }
    var showZipDialogForPaths by remember { mutableStateOf<List<String>?>(null) }
    var renameTarget by remember { mutableStateOf<FileEntryItem?>(null) }
    var deleteTargets by remember { mutableStateOf<List<String>?>(null) }
    var textEditorTarget by remember { mutableStateOf<FileEntryItem?>(null) }
    var imagePreviewTarget by remember { mutableStateOf<FileEntryItem?>(null) }
    var exportTargetPath by remember { mutableStateOf<String?>(null) }

    val importFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            fmViewModel.importExternalUrisHere(uris)
        }
    }

    val importFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            fmViewModel.importExternalFolderHere(treeUri)
        }
    }

    val exportFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val path = exportTargetPath
        if (uri != null && path != null) {
            fmViewModel.exportFileToUri(path, uri)
        }
        exportTargetPath = null
    }

    val isInSubfolder = uiState.currentPath != uiState.rootPath
    BackHandler {
        when {
            uiState.selectedPaths.isNotEmpty() -> fmViewModel.clearSelection()
            isInSubfolder -> fmViewModel.navigateUp()
            else -> onBackToBrowseHome()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Top Header or Multi-Select Action Bar
            if (uiState.selectedPaths.isNotEmpty()) {
                Surface(
                    color = scheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { fmViewModel.clearSelection() }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                            Text(
                                text = "${uiState.selectedPaths.size} selected",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                fontFamily = JakartaFontFamily
                            )
                        }
                        Row {
                            IconButton(onClick = { fmViewModel.copySelectedToClipboard() }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                            }
                            IconButton(onClick = { fmViewModel.cutSelectedToClipboard() }) {
                                Icon(Icons.Default.ContentCut, contentDescription = "Move")
                            }
                            IconButton(onClick = {
                                fmViewModel.encryptEntriesToLocker(uiState.selectedPaths.toList(), shredOriginals = false)
                            }) {
                                Icon(Icons.Default.Lock, contentDescription = "Move to Safe Folder")
                            }
                            IconButton(onClick = {
                                showZipDialogForPaths = uiState.selectedPaths.toList()
                            }) {
                                Icon(Icons.Default.FolderZip, contentDescription = "Compress to ZIP")
                            }
                            IconButton(onClick = {
                                deleteTargets = uiState.selectedPaths.toList()
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = scheme.error)
                            }
                        }
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = {
                                if (isInSubfolder) fmViewModel.navigateUp() else onBackToBrowseHome()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                        Column {
                            val folderTitle = uiState.breadcrumbs.lastOrNull()?.label ?: uiState.activeRoot.title
                            Text(
                                text = folderTitle,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = JakartaFontFamily
                            )
                            Text(
                                text = "${uiState.entries.size} items",
                                fontSize = 12.sp,
                                color = scheme.onSurfaceVariant,
                                fontFamily = JakartaFontFamily
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showSearchBar = !showSearchBar }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = { fmViewModel.toggleGridView() }) {
                            Icon(
                                imageVector = if (uiState.isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                                contentDescription = "View mode"
                            )
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                FileSortField.values().forEach { field ->
                                    DropdownMenuItem(
                                        text = { Text("Sort by ${field.label}", fontFamily = JakartaFontFamily) },
                                        onClick = {
                                            fmViewModel.setSort(field)
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Breadcrumb Row
            if (uiState.breadcrumbs.size > 1) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    items(uiState.breadcrumbs.size) { index ->
                        val crumb = uiState.breadcrumbs[index]
                        val isLast = index == uiState.breadcrumbs.lastIndex
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = crumb.label,
                                fontSize = 12.sp,
                                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium,
                                color = if (isLast) scheme.primary else scheme.onSurfaceVariant,
                                fontFamily = JakartaFontFamily,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { fmViewModel.navigateToPath(crumb.absolutePath) }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                            if (!isLast) {
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = scheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (showSearchBar) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { fmViewModel.setSearchQuery(it) },
                    placeholder = { Text("Search in folder...", fontSize = 13.sp, fontFamily = JakartaFontFamily) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { fmViewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
            }

            // Clipboard Paste Banner
            if (uiState.clipboardPaths.isNotEmpty()) {
                Surface(
                    color = scheme.secondaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "${uiState.clipboardPaths.size} item(s) ready to ${if (uiState.clipboardMode == ClipboardMode.COPY) "copy" else "move"}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSecondaryContainer,
                            fontFamily = JakartaFontFamily
                        )
                        Row {
                            TextButton(onClick = { fmViewModel.clearClipboard() }) {
                                Text("Cancel", fontSize = 12.sp)
                            }
                            Button(
                                onClick = { fmViewModel.pasteClipboardHere() },
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Paste here", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            notice?.let { msg ->
                Surface(
                    color = scheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable { fmViewModel.clearNotice() }
                ) {
                    Text(
                        text = msg,
                        fontSize = 12.sp,
                        color = scheme.onPrimaryContainer,
                        fontFamily = JakartaFontFamily,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // File List or Grid
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (uiState.entries.isEmpty()) {
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
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(scheme.surfaceContainerLow)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "This folder is empty",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = JakartaFontFamily,
                            color = scheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap '+ New' below to add a folder, note, or file.",
                            fontSize = 13.sp,
                            color = scheme.onSurfaceVariant,
                            fontFamily = JakartaFontFamily
                        )
                    }
                }
            } else if (uiState.isGridView) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(uiState.entries, key = { it.path }) { entry ->
                        val isSelected = entry.path in uiState.selectedPaths
                        CleanFileRowCard(
                            entry = entry,
                            isSelected = isSelected,
                            onClick = {
                                if (uiState.selectedPaths.isNotEmpty()) {
                                    fmViewModel.toggleSelection(entry.path)
                                } else {
                                    handleFileOpen(
                                        entry = entry,
                                        fmViewModel = fmViewModel,
                                        onOpenTextEditor = { textEditorTarget = it },
                                        onOpenImagePreview = { imagePreviewTarget = it }
                                    )
                                }
                            },
                            onLongClick = { fmViewModel.toggleSelection(entry.path) },
                            onCopy = {
                                fmViewModel.clearSelection()
                                fmViewModel.toggleSelection(entry.path)
                                fmViewModel.copySelectedToClipboard()
                            },
                            onMove = {
                                fmViewModel.clearSelection()
                                fmViewModel.toggleSelection(entry.path)
                                fmViewModel.cutSelectedToClipboard()
                            },
                            onRename = { renameTarget = entry },
                            onCompressZip = { showZipDialogForPaths = listOf(entry.path) },
                            onExtractZip = { fmViewModel.extractZipArchive(entry) },
                            onMoveToSafeFolder = {
                                fmViewModel.encryptEntriesToLocker(listOf(entry.path), shredOriginals = false)
                            },
                            onExport = {
                                exportTargetPath = entry.path
                                exportFileLauncher.launch(entry.name)
                            },
                            onDelete = { deleteTargets = listOf(entry.path) }
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(uiState.entries, key = { it.path }) { entry ->
                        val isSelected = entry.path in uiState.selectedPaths
                        CleanFileRowCard(
                            entry = entry,
                            isSelected = isSelected,
                            onClick = {
                                if (uiState.selectedPaths.isNotEmpty()) {
                                    fmViewModel.toggleSelection(entry.path)
                                } else {
                                    handleFileOpen(
                                        entry = entry,
                                        fmViewModel = fmViewModel,
                                        onOpenTextEditor = { textEditorTarget = it },
                                        onOpenImagePreview = { imagePreviewTarget = it }
                                    )
                                }
                            },
                            onLongClick = { fmViewModel.toggleSelection(entry.path) },
                            onCopy = {
                                fmViewModel.clearSelection()
                                fmViewModel.toggleSelection(entry.path)
                                fmViewModel.copySelectedToClipboard()
                            },
                            onMove = {
                                fmViewModel.clearSelection()
                                fmViewModel.toggleSelection(entry.path)
                                fmViewModel.cutSelectedToClipboard()
                            },
                            onRename = { renameTarget = entry },
                            onCompressZip = { showZipDialogForPaths = listOf(entry.path) },
                            onExtractZip = { fmViewModel.extractZipArchive(entry) },
                            onMoveToSafeFolder = {
                                fmViewModel.encryptEntriesToLocker(listOf(entry.path), shredOriginals = false)
                            },
                            onExport = {
                                exportTargetPath = entry.path
                                exportFileLauncher.launch(entry.name)
                            },
                            onDelete = { deleteTargets = listOf(entry.path) }
                        )
                    }
                }
            }
        }

        // Clean Google Files "+ New" Button
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            ExtendedFloatingActionButton(
                onClick = { showCreateMenu = true },
                containerColor = scheme.primary,
                contentColor = scheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New", fontFamily = JakartaFontFamily, fontWeight = FontWeight.SemiBold) }
            )

            DropdownMenu(
                expanded = showCreateMenu,
                onDismissRequest = { showCreateMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("New folder", fontFamily = JakartaFontFamily) },
                    leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                    onClick = {
                        showCreateMenu = false
                        showNewFolderDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("New text note", fontFamily = JakartaFontFamily) },
                    leadingIcon = { Icon(Icons.Default.NoteAdd, contentDescription = null) },
                    onClick = {
                        showCreateMenu = false
                        showNewNoteDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("Import files", fontFamily = JakartaFontFamily) },
                    leadingIcon = { Icon(Icons.Default.UploadFile, contentDescription = null) },
                    onClick = {
                        showCreateMenu = false
                        importFilesLauncher.launch(arrayOf("*/*"))
                    }
                )
                DropdownMenuItem(
                    text = { Text("Import folder", fontFamily = JakartaFontFamily) },
                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    onClick = {
                        showCreateMenu = false
                        importFolderLauncher.launch(null)
                    }
                )
            }
        }
    }

    // Dialogs
    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text("New folder", fontWeight = FontWeight.Bold, fontFamily = JakartaFontFamily) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder name", fontFamily = JakartaFontFamily) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (folderName.isNotBlank()) {
                            fmViewModel.createNewFolder(folderName)
                            showNewFolderDialog = false
                        }
                    },
                    shape = RoundedCornerShape(50)
                ) {
                    Text("Create", fontFamily = JakartaFontFamily, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("Cancel", fontFamily = JakartaFontFamily)
                }
            }
        )
    }

    if (showNewNoteDialog) {
        var fileName by remember { mutableStateOf("Note.txt") }
        var content by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewNoteDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text("New text note", fontWeight = FontWeight.Bold, fontFamily = JakartaFontFamily) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        label = { Text("File name", fontFamily = JakartaFontFamily) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("Content", fontFamily = JakartaFontFamily) },
                        minLines = 4,
                        maxLines = 8,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (fileName.isNotBlank()) {
                            fmViewModel.createNewTextFile(fileName, content)
                            showNewNoteDialog = false
                        }
                    },
                    shape = RoundedCornerShape(50)
                ) {
                    Text("Save", fontFamily = JakartaFontFamily, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewNoteDialog = false }) {
                    Text("Cancel", fontFamily = JakartaFontFamily)
                }
            }
        )
    }

    renameTarget?.let { target ->
        var newName by remember(target.path) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            shape = RoundedCornerShape(24.dp),
            title = { Text("Rename", fontWeight = FontWeight.Bold, fontFamily = JakartaFontFamily) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            fmViewModel.renameEntry(target, newName)
                            renameTarget = null
                        }
                    },
                    shape = RoundedCornerShape(50)
                ) {
                    Text("Rename", fontFamily = JakartaFontFamily, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel", fontFamily = JakartaFontFamily)
                }
            }
        )
    }

    showZipDialogForPaths?.let { paths ->
        var zipName by remember { mutableStateOf("Archive.zip") }
        AlertDialog(
            onDismissRequest = { showZipDialogForPaths = null },
            shape = RoundedCornerShape(24.dp),
            title = { Text("Compress to ZIP", fontWeight = FontWeight.Bold, fontFamily = JakartaFontFamily) },
            text = {
                OutlinedTextField(
                    value = zipName,
                    onValueChange = { zipName = it },
                    label = { Text("Archive name", fontFamily = JakartaFontFamily) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        fmViewModel.compressToZip(paths, zipName)
                        showZipDialogForPaths = null
                    },
                    shape = RoundedCornerShape(50)
                ) {
                    Text("Compress", fontFamily = JakartaFontFamily, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showZipDialogForPaths = null }) {
                    Text("Cancel", fontFamily = JakartaFontFamily)
                }
            }
        )
    }

    deleteTargets?.let { paths ->
        AlertDialog(
            onDismissRequest = { deleteTargets = null },
            shape = RoundedCornerShape(24.dp),
            title = { Text("Delete ${paths.size} item(s)?", fontWeight = FontWeight.Bold, fontFamily = JakartaFontFamily) },
            text = {
                Text("This will permanently remove the selected items.", fontFamily = JakartaFontFamily)
            },
            confirmButton = {
                Button(
                    onClick = {
                        fmViewModel.deleteEntries(paths)
                        deleteTargets = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.error),
                    shape = RoundedCornerShape(50)
                ) {
                    Text("Delete", fontFamily = JakartaFontFamily, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargets = null }) {
                    Text("Cancel", fontFamily = JakartaFontFamily)
                }
            }
        )
    }

    textEditorTarget?.let { target ->
        val loadedText by produceState<String?>(initialValue = null, target.path) {
            value = fmViewModel.readTextFileContent(target.path)
        }
        var editedText by remember(loadedText) { mutableStateOf(loadedText ?: "") }
        AlertDialog(
            onDismissRequest = { textEditorTarget = null },
            shape = RoundedCornerShape(24.dp),
            title = { Text(target.name, fontWeight = FontWeight.Bold, fontFamily = JakartaFontFamily) },
            text = {
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    minLines = 6,
                    maxLines = 12,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        fmViewModel.saveTextFileContent(target.path, editedText)
                        textEditorTarget = null
                    },
                    shape = RoundedCornerShape(50)
                ) {
                    Text("Save", fontFamily = JakartaFontFamily, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { textEditorTarget = null }) {
                    Text("Close", fontFamily = JakartaFontFamily)
                }
            }
        )
    }

    imagePreviewTarget?.let { target ->
        val bmp by produceState<ImageBitmap?>(initialValue = null, target.path) {
            value = try {
                BitmapFactory.decodeFile(target.path)?.asImageBitmap()
            } catch (_: Throwable) { null }
        }
        AlertDialog(
            onDismissRequest = { imagePreviewTarget = null },
            shape = RoundedCornerShape(24.dp),
            title = { Text(target.name, fontWeight = FontWeight.Bold, fontFamily = JakartaFontFamily) },
            text = {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(scheme.surfaceContainerLow)
                ) {
                    val image = bmp
                    if (image != null) {
                        Image(
                            bitmap = image,
                            contentDescription = target.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        fmViewModel.encryptEntriesToLocker(listOf(target.path), shredOriginals = false)
                        imagePreviewTarget = null
                    },
                    shape = RoundedCornerShape(50)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Move to Safe Folder", fontFamily = JakartaFontFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { imagePreviewTarget = null }) {
                    Text("Close", fontFamily = JakartaFontFamily)
                }
            }
        )
    }
}

private fun handleFileOpen(
    entry: FileEntryItem,
    fmViewModel: FileManagerViewModel,
    onOpenTextEditor: (FileEntryItem) -> Unit,
    onOpenImagePreview: (FileEntryItem) -> Unit
) {
    when {
        entry.isDirectory -> fmViewModel.navigateToPath(entry.path)
        entry.category == FileCategory.IMAGE -> onOpenImagePreview(entry)
        entry.category == FileCategory.ARCHIVE && entry.extension == "zip" -> fmViewModel.extractZipArchive(entry)
        else -> onOpenTextEditor(entry)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CleanFileRowCard(
    entry: FileEntryItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onCompressZip: () -> Unit,
    onExtractZip: () -> Unit,
    onMoveToSafeFolder: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var showMenu by remember { mutableStateOf(false) }

    val (icon, tint) = when {
        entry.isDirectory -> Icons.Default.Folder to scheme.primary
        entry.category == FileCategory.IMAGE -> Icons.Default.Image to Color(0xFFD93025)
        entry.category == FileCategory.VIDEO -> Icons.Default.VideoFile to Color(0xFF188038)
        entry.category == FileCategory.AUDIO -> Icons.Default.AudioFile to Color(0xFFE37400)
        entry.category == FileCategory.ARCHIVE -> Icons.Default.FolderZip to Color(0xFF0B57D0)
        entry.category == FileCategory.CODE_OR_TEXT || entry.category == FileCategory.PDF_OR_DOC ->
            Icons.Default.Description to Color(0xFF7627BB)
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to scheme.onSurfaceVariant
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) scheme.primaryContainer.copy(alpha = 0.45f) else scheme.surface
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            if (isSelected) scheme.primary else scheme.outlineVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.12f))
            ) {
                Icon(
                    imageVector = if (isSelected) Icons.Default.CheckCircle else icon,
                    contentDescription = null,
                    tint = if (isSelected) scheme.primary else tint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = JakartaFontFamily
                )
                val dateStr = remember(entry.lastModified) {
                    if (entry.lastModified > 0L) {
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.lastModified))
                    } else ""
                }
                val metaText = if (entry.isDirectory) {
                    "${entry.childCount} items • $dateStr"
                } else {
                    "${formatBytes(entry.sizeBytes)} • $dateStr"
                }
                Text(
                    text = metaText,
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = JakartaFontFamily
                )
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    if (!entry.isDirectory) {
                        DropdownMenuItem(
                            text = { Text("Move to Safe Folder", fontFamily = JakartaFontFamily) },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onMoveToSafeFolder()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Copy", fontFamily = JakartaFontFamily) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onCopy()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Move", fontFamily = JakartaFontFamily) },
                        leadingIcon = { Icon(Icons.Default.ContentCut, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onMove()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename", fontFamily = JakartaFontFamily) },
                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onRename()
                        }
                    )
                    if (entry.extension == "zip") {
                        DropdownMenuItem(
                            text = { Text("Extract ZIP here", fontFamily = JakartaFontFamily) },
                            leadingIcon = { Icon(Icons.Default.Unarchive, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onExtractZip()
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Compress to ZIP", fontFamily = JakartaFontFamily) },
                            leadingIcon = { Icon(Icons.Default.FolderZip, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onCompressZip()
                            }
                        )
                    }
                    if (!entry.isDirectory) {
                        DropdownMenuItem(
                            text = { Text("Save / Share", fontFamily = JakartaFontFamily) },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onExport()
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Delete", color = scheme.error, fontFamily = JakartaFontFamily) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = scheme.error) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}
