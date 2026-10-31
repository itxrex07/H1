package com.hollow.app.isolation.feature.gallery

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hollow.app.isolation.mount.FileKind
import com.hollow.app.isolation.ui.viewmodel.AppViewModelProvider
import com.hollow.app.isolation.util.formatBytes
import com.hollow.app.ui.theme.JakartaFontFamily

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    gallery: GalleryViewModel = viewModel(factory = AppViewModelProvider.Factory),
    initialMode: String = "PHOTOS", // "PHOTOS", "VIDEOS", "AUDIO", "STARRED", "ALBUMS"
    onBack: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val proState by gallery.proState.collectAsState()
    val notice by gallery.notice.collectAsState()

    var activePill by remember { mutableStateOf(initialMode) }
    var activeLightboxItem by remember { mutableStateOf<GalleryMediaItem?>(null) }
    var showCreateAlbumDialog by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(false) }
    var exportTargetMedia by remember { mutableStateOf<GalleryMediaItem?>(null) }

    LaunchedEffect(initialMode) {
        activePill = initialMode
        when (initialMode) {
            "VIDEOS" -> {
                gallery.setSourceTab(GallerySourceTab.WORKSPACE_ALBUMS)
                gallery.setLayoutMode(GalleryLayoutMode.TIMELINE)
                gallery.setMediaFilter(MediaFilterKind.VIDEO)
            }
            "AUDIO" -> {
                gallery.setSourceTab(GallerySourceTab.WORKSPACE_ALBUMS)
                gallery.setLayoutMode(GalleryLayoutMode.TIMELINE)
                gallery.setMediaFilter(MediaFilterKind.AUDIO)
            }
            "STARRED" -> {
                gallery.setSourceTab(GallerySourceTab.WORKSPACE_ALBUMS)
                gallery.setLayoutMode(GalleryLayoutMode.FAVORITES)
                gallery.setMediaFilter(MediaFilterKind.ALL)
            }
            "ALBUMS" -> {
                gallery.setSourceTab(GallerySourceTab.WORKSPACE_ALBUMS)
                gallery.setLayoutMode(GalleryLayoutMode.ALBUMS)
                gallery.setMediaFilter(MediaFilterKind.ALL)
            }
            else -> {
                gallery.setSourceTab(GallerySourceTab.WORKSPACE_ALBUMS)
                gallery.setLayoutMode(GalleryLayoutMode.TIMELINE)
                gallery.setMediaFilter(MediaFilterKind.ALL)
            }
        }
    }

    val importMediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val targetAlbum = proState.activeAlbum ?: "Camera Roll"
            gallery.importUrisToGallery(uris, targetAlbum)
        }
    }

    val exportMediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val item = exportTargetMedia
        if (uri != null && item != null) {
            gallery.exportMediaItem(item, uri)
        }
        exportTargetMedia = null
    }

    BackHandler(enabled = proState.selectedIds.isNotEmpty() || proState.activeAlbum != null || onBack != null) {
        when {
            proState.selectedIds.isNotEmpty() -> gallery.clearSelection()
            proState.activeAlbum != null -> {
                gallery.selectAlbum(null)
                gallery.setLayoutMode(GalleryLayoutMode.ALBUMS)
                activePill = "ALBUMS"
            }
            onBack != null -> onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("gallery_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Clean Header Row (or Selection Bar when items are selected)
            if (proState.selectedIds.isNotEmpty()) {
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
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { gallery.clearSelection() }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                            Text(
                                text = "${proState.selectedIds.size} selected",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                fontFamily = JakartaFontFamily
                            )
                        }
                        Row {
                            IconButton(onClick = { gallery.batchLockSelectedToVault() }) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Move to Safe Folder",
                                    tint = scheme.primary
                                )
                            }
                            IconButton(onClick = { gallery.batchDeleteSelected() }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = scheme.error
                                )
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onBack != null || proState.activeAlbum != null) {
                            IconButton(
                                onClick = {
                                    if (proState.activeAlbum != null) {
                                        gallery.selectAlbum(null)
                                        gallery.setLayoutMode(GalleryLayoutMode.ALBUMS)
                                        activePill = "ALBUMS"
                                    } else {
                                        onBack?.invoke()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back"
                                )
                            }
                        }
                        Column {
                            Text(
                                text = proState.activeAlbum ?: "Photos & Media",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = JakartaFontFamily,
                                color = scheme.onSurface
                            )
                            Text(
                                text = "${proState.items.size} items",
                                fontSize = 12.sp,
                                color = scheme.onSurfaceVariant,
                                fontFamily = JakartaFontFamily
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showSearchBar = !showSearchBar }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = scheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showCreateAlbumDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.CreateNewFolder,
                                contentDescription = "New Album",
                                tint = scheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (showSearchBar) {
                OutlinedTextField(
                    value = proState.searchQuery,
                    onValueChange = { gallery.setSearchQuery(it) },
                    placeholder = { Text("Search photos...", fontSize = 13.sp, fontFamily = JakartaFontFamily) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (proState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { gallery.setSearchQuery("") }) {
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

            // Simple Google Photos / Google Files Filter Pills
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                val pills = listOf(
                    Triple("PHOTOS", "All Photos", Icons.Default.Image),
                    Triple("ALBUMS", "Albums", Icons.Default.PhotoAlbum),
                    Triple("STARRED", "Starred", Icons.Default.Star),
                    Triple("SAFE", "Safe Folder", Icons.Default.Lock),
                    Triple("DEVICE", "Phone Gallery", Icons.Default.Folder)
                )
                items(pills.size) { index ->
                    val (key, label, icon) = pills[index]
                    val selected = activePill == key
                    FilterChip(
                        selected = selected,
                        onClick = {
                            activePill = key
                            when (key) {
                                "PHOTOS" -> {
                                    gallery.setSourceTab(GallerySourceTab.WORKSPACE_ALBUMS)
                                    gallery.selectAlbum(null)
                                    gallery.setLayoutMode(GalleryLayoutMode.TIMELINE)
                                    gallery.setMediaFilter(MediaFilterKind.ALL)
                                }
                                "ALBUMS" -> {
                                    gallery.setSourceTab(GallerySourceTab.WORKSPACE_ALBUMS)
                                    gallery.selectAlbum(null)
                                    gallery.setLayoutMode(GalleryLayoutMode.ALBUMS)
                                }
                                "STARRED" -> {
                                    gallery.setSourceTab(GallerySourceTab.WORKSPACE_ALBUMS)
                                    gallery.selectAlbum(null)
                                    gallery.setLayoutMode(GalleryLayoutMode.FAVORITES)
                                }
                                "SAFE" -> {
                                    gallery.setSourceTab(GallerySourceTab.ENCRYPTED_LOCKER)
                                }
                                "DEVICE" -> {
                                    gallery.setSourceTab(GallerySourceTab.DEVICE_MEDIA)
                                }
                            }
                        },
                        label = { Text(label, fontFamily = JakartaFontFamily, fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        shape = RoundedCornerShape(50),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = scheme.primaryContainer,
                            selectedLabelColor = scheme.onPrimaryContainer,
                            selectedLeadingIconColor = scheme.onPrimaryContainer,
                            containerColor = scheme.surface,
                            labelColor = scheme.onSurfaceVariant
                        )
                    )
                }
            }

            notice?.let { msg ->
                Surface(
                    color = scheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable { gallery.clearNotice() }
                ) {
                    Text(
                        text = msg,
                        fontSize = 12.sp,
                        color = scheme.onSecondaryContainer,
                        fontFamily = JakartaFontFamily,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // Main Content: Albums Grid OR Clean 3-Column Photo Grid
            if (proState.isLoading) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (activePill == "ALBUMS" && proState.activeAlbum == null) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 90.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(proState.albums, key = { it.name }) { album ->
                        CleanAlbumCard(
                            album = album,
                            gallery = gallery,
                            onClick = {
                                gallery.selectAlbum(album.name)
                                activePill = "PHOTOS"
                            }
                        )
                    }
                }
            } else if (proState.items.isEmpty()) {
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
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(scheme.surfaceContainerLow)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No photos here yet",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = JakartaFontFamily,
                            color = scheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap 'Add Photos' below to import pictures into Hollow.",
                            fontSize = 13.sp,
                            color = scheme.onSurfaceVariant,
                            fontFamily = JakartaFontFamily
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(proState.items, key = { it.id }) { item ->
                        val isSelected = item.id in proState.selectedIds
                        CleanPhotoTile(
                            item = item,
                            isSelected = isSelected,
                            gallery = gallery,
                            onClick = {
                                if (proState.selectedIds.isNotEmpty()) {
                                    gallery.toggleSelectItem(item.id)
                                } else {
                                    activeLightboxItem = item
                                }
                            },
                            onLongClick = {
                                gallery.toggleSelectItem(item.id)
                            }
                        )
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { importMediaLauncher.launch(arrayOf("image/*", "video/*")) },
            containerColor = scheme.primary,
            contentColor = scheme.onPrimary,
            shape = RoundedCornerShape(16.dp),
            icon = { Icon(Icons.Default.AddPhotoAlternate, contentDescription = null) },
            text = { Text("Add Photos", fontFamily = JakartaFontFamily, fontWeight = FontWeight.SemiBold) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
    }

    // Clean Full-Screen Photo Lightbox
    activeLightboxItem?.let { currentItem ->
        CleanPhotoLightbox(
            item = currentItem,
            gallery = gallery,
            onDismiss = { activeLightboxItem = null },
            onToggleFavorite = {
                gallery.toggleFavorite(currentItem)
                activeLightboxItem = currentItem.copy(isFavorite = !currentItem.isFavorite)
            },
            onMoveToSafeFolder = {
                gallery.lockItemToVault(currentItem)
                activeLightboxItem = null
            },
            onExport = {
                exportTargetMedia = currentItem
                exportMediaLauncher.launch(currentItem.name)
            },
            onDelete = {
                gallery.deleteMediaItem(currentItem)
                activeLightboxItem = null
            }
        )
    }

    if (showCreateAlbumDialog) {
        var albumName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateAlbumDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = {
                Text("New Album", fontWeight = FontWeight.Bold, fontFamily = JakartaFontFamily)
            },
            text = {
                OutlinedTextField(
                    value = albumName,
                    onValueChange = { albumName = it },
                    label = { Text("Album name", fontFamily = JakartaFontFamily) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (albumName.isNotBlank()) {
                            gallery.createNewAlbum(albumName.trim())
                            showCreateAlbumDialog = false
                        }
                    },
                    shape = RoundedCornerShape(50)
                ) {
                    Text("Create", fontFamily = JakartaFontFamily, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateAlbumDialog = false }) {
                    Text("Cancel", fontFamily = JakartaFontFamily)
                }
            }
        )
    }
}

@Composable
private fun CleanAlbumCard(
    album: GalleryAlbumSummary,
    gallery: GalleryViewModel,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val coverBitmap by produceState<ImageBitmap?>(initialValue = null, album.coverItem?.id) {
        value = album.coverItem?.let { gallery.loadMediaBitmap(it, fullRes = false)?.asImageBitmap() }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.25f)
                    .background(scheme.surfaceContainerLow)
            ) {
                val bmp = coverBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = album.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PhotoAlbum,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = album.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = JakartaFontFamily
                )
                Text(
                    text = "${album.itemCount} items • ${formatBytes(album.totalBytes)}",
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                    fontFamily = JakartaFontFamily
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CleanPhotoTile(
    item: GalleryMediaItem,
    isSelected: Boolean,
    gallery: GalleryViewModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val thumb by produceState<ImageBitmap?>(initialValue = null, item.id, item.modifiedMillis) {
        if (item.kind == FileKind.IMAGE) {
            value = gallery.loadMediaBitmap(item, fullRes = false)?.asImageBitmap()
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceContainerLow)
            .then(
                if (isSelected) Modifier.border(3.dp, scheme.primary, RoundedCornerShape(12.dp))
                else Modifier
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        val bmp = thumb
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = when (item.kind) {
                        FileKind.VIDEO -> Icons.Default.Movie
                        FileKind.AUDIO -> Icons.Default.MusicNote
                        else -> Icons.Default.Image
                    },
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        if (item.isFavorite) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = "Favorite",
                tint = Color(0xFFFF4D6D),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(16.dp)
            )
        }

        if (item.isEncrypted) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Locked",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .size(14.dp)
            )
        }

        if (isSelected) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(scheme.primary)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun CleanPhotoLightbox(
    item: GalleryMediaItem,
    gallery: GalleryViewModel,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMoveToSafeFolder: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val fullBitmap by produceState<ImageBitmap?>(initialValue = null, item.id, refreshTrigger) {
        value = gallery.loadMediaBitmap(item, fullRes = true)?.asImageBitmap()
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0E1210))
        ) {
            // Photo Canvas with Pinch-to-Zoom & Double-Tap
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.05f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.4f
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 4f)
                            scale = newScale
                            offset = if (newScale > 1f) offset + pan else Offset.Zero
                        }
                    }
            ) {
                val bmp = fullBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                    )
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }

            // Simple Top Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Column {
                        Text(
                            text = item.name,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = JakartaFontFamily
                        )
                        Text(
                            text = "${item.albumName} • ${formatBytes(item.sizeBytes)}",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 12.sp,
                            fontFamily = JakartaFontFamily
                        )
                    }
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (item.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Star",
                        tint = if (item.isFavorite) Color(0xFFFF4D6D) else Color.White
                    )
                }
            }

            // Clean Bottom Action Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(vertical = 12.dp, horizontal = 16.dp)
            ) {
                if (item.localFile != null) {
                    LightboxBottomButton(
                        icon = Icons.Default.RotateRight,
                        label = "Rotate",
                        onClick = {
                            gallery.saveEditedImage(
                                item = item,
                                rotationDegrees = 90f,
                                flipHorizontal = false,
                                filterPreset = PhotoFilterPreset.NONE,
                                overwrite = true
                            )
                            refreshTrigger++
                        }
                    )
                }
                if (!item.isEncrypted && item.localFile != null) {
                    LightboxBottomButton(
                        icon = Icons.Default.Lock,
                        label = "Safe Folder",
                        onClick = onMoveToSafeFolder
                    )
                }
                LightboxBottomButton(
                    icon = Icons.Default.Share,
                    label = "Save",
                    onClick = onExport
                )
                LightboxBottomButton(
                    icon = Icons.Default.Delete,
                    label = "Delete",
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
private fun LightboxBottomButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, color = Color.White, fontSize = 11.sp, fontFamily = JakartaFontFamily)
    }
}
