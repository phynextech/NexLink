package com.phynex.NexLink.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import com.phynex.NexLink.model.FileItem
import com.phynex.NexLink.ui.theme.*
import com.phynex.NexLink.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PREVIEW_SIZE_LIMIT = 150L * 1024 * 1024

enum class SortOption { Name, Date, Size }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val fileList by viewModel.fileList.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()
    val transferProgress by viewModel.fileTransferProgress.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val filePreviewData by viewModel.filePreviewData.collectAsState()

    val pathStack = remember { mutableStateListOf("root") }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var selectedFile by remember { mutableStateOf<FileItem?>(null) }
    var showPreviewSheet by remember { mutableStateOf(false) }

    // UI state
    var isGridView by remember { mutableStateOf(false) }
    var zoomScale by remember { mutableStateOf(1f) }
    var sortOption by remember { mutableStateOf(SortOption.Name) }
    var sortAscending by remember { mutableStateOf(true) }

    // Selection
    val selectedItems = remember { mutableStateListOf<FileItem>() }
    val isSelectionMode = selectedItems.isNotEmpty()

    // Dialogs
    var showSortMenu by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var createType by remember { mutableStateOf("folder") }
    var showRenameDialog by remember { mutableStateOf<FileItem?>(null) }
    var showDeleteDialog by remember { mutableStateOf<List<FileItem>?>(null) }
    var showPropertiesDialog by remember { mutableStateOf<FileItem?>(null) }

    // Clipboard for copy/move
    var clipboardAction by remember { mutableStateOf<String?>(null) } // "copy" or "move"
    var clipboardItems by remember { mutableStateOf<List<FileItem>>(emptyList()) }

    var pendingOpenWith by remember { mutableStateOf<FileItem?>(null) }
    var pendingShare by remember { mutableStateOf<FileItem?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    androidx.activity.compose.BackHandler {
        if (isSelectionMode) {
            selectedItems.clear()
        } else if (pathStack.size > 1) {
            pathStack.removeLast()
            isLoading = true
            viewModel.browsePath(pathStack.last())
        } else {
            onBack()
        }
    }

    LaunchedEffect(transferProgress) {
        if (transferProgress == null) {
            val openFile = pendingOpenWith
            val shareFile = pendingShare
            
            if (openFile != null) {
                pendingOpenWith = null
                try {
                    val downloadedFile = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NexLink/${openFile.name}")
                    if (downloadedFile.exists()) {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", downloadedFile)
                        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(openFile.type.substringAfterLast(".").lowercase()) ?: "*/*"
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Open with..."))
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            
            if (shareFile != null) {
                pendingShare = null
                try {
                    val downloadedFile = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NexLink/${shareFile.name}")
                    if (downloadedFile.exists()) {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", downloadedFile)
                        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(shareFile.type.substringAfterLast(".").lowercase()) ?: "*/*"
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = mime
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share via..."))
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    // Load root on enter
    LaunchedEffect(Unit) {
        isLoading = true
        loadError = false
        viewModel.browsePath("root")
        delay(5_000)
        if (isLoading && fileList.isEmpty()) {
            loadError = true
            isLoading = false
        }
    }

    LaunchedEffect(fileList) {
        if (fileList.isNotEmpty() || currentPath != "root") {
            isLoading = false
            loadError = false
            selectedItems.clear() // Clear selection on navigate
        }
    }

    LaunchedEffect(currentPath) {
        while (isActive) {
            delay(2_000)
            if (!isLoading && isConnected)
                viewModel.browsePath(pathStack.lastOrNull() ?: "root")
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val sortedList = remember(fileList, sortOption, sortAscending) {
        var list = fileList
        list = when (sortOption) {
            SortOption.Name -> list.sortedBy { it.name.lowercase() }
            SortOption.Date -> list.sortedBy { it.lastModified }
            SortOption.Size -> list.sortedBy { it.size }
        }
        if (!sortAscending) list = list.reversed()
        // Always put directories first
        list.sortedByDescending { it.isDirectory }
    }

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            if (!isSelectionMode && currentPath != "root") {
                FloatingActionButton(
                    onClick = { showCreateDialog = true },
                    containerColor = primary,
                    contentColor = Color.Black,
                    elevation = FloatingActionButtonDefaults.elevation(8.dp)
                ) {
                    Icon(Icons.Default.Add, "Create")
                }
            }
        }
    ) { paddingVals ->
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0F0F1A), Color(0xFF05050A)))
        )) {
            // Glowing orb effects in the background
            Box(Modifier.align(Alignment.TopStart).offset(x = (-50).dp, y = (-50).dp).size(200.dp)
                .background(Brush.radialGradient(listOf(primary.copy(0.15f), Color.Transparent)), CircleShape))
            Box(Modifier.align(Alignment.BottomEnd).offset(x = 50.dp, y = 50.dp).size(300.dp)
                .background(Brush.radialGradient(listOf(Color(0xFF3B82F6).copy(0.1f), Color.Transparent)), CircleShape))

            Column(Modifier.fillMaxSize().padding(paddingVals)) {
                // ── Top bar ───────────────────────────────────────────────
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSelectionMode) {
                        IconButton(onClick = { selectedItems.clear() }) {
                            Icon(Icons.Default.Close, "Cancel", tint = onBackground)
                        }
                        Text("${selectedItems.size} selected", style = MaterialTheme.typography.titleMedium, color = onBackground, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            if (selectedItems.size == fileList.size) selectedItems.clear()
                            else { selectedItems.clear(); selectedItems.addAll(fileList) }
                        }) {
                            Icon(Icons.Default.SelectAll, "Select All", tint = onBackground)
                        }
                        Box {
                            var showBulkMenu by remember { mutableStateOf(false) }
                            IconButton(onClick = { showBulkMenu = true }) {
                                Icon(Icons.Default.MoreVert, "More", tint = onBackground)
                            }
                            DropdownMenu(expanded = showBulkMenu, onDismissRequest = { showBulkMenu = false }, modifier = Modifier.background(surface)) {
                                DropdownMenuItem(text = { Text("Copy", color = onBackground) }, onClick = { clipboardAction = "copy"; clipboardItems = selectedItems.toList(); selectedItems.clear(); showBulkMenu = false })
                                DropdownMenuItem(text = { Text("Move", color = onBackground) }, onClick = { clipboardAction = "move"; clipboardItems = selectedItems.toList(); selectedItems.clear(); showBulkMenu = false })
                                DropdownMenuItem(text = { Text("Delete", color = RedDisconnected) }, onClick = { showDeleteDialog = selectedItems.toList(); showBulkMenu = false })
                            }
                        }
                    } else {
                        IconButton(onClick = {
                            if (pathStack.size > 1) {
                                pathStack.removeLast()
                                isLoading = true
                                viewModel.browsePath(pathStack.last())
                            } else onBack()
                        }) {
                            Icon(Icons.Default.ArrowBack, "Back", tint = onBackground)
                        }
                        Spacer(Modifier.width(8.dp))
                        val currentPathStr = pathStack.lastOrNull() ?: "root"
                        val pathParts = if (currentPathStr == "root") listOf("Home") else currentPathStr.split(Regex("[/\\\\]")).filter { it.isNotEmpty() }
                        
                        LazyRow(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            itemsIndexed(pathParts) { index, part ->
                                val isLast = index == pathParts.lastIndex
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isLast) primary.copy(0.15f) else Color.Transparent)
                                        .clickable {
                                            if (!isLast && currentPathStr != "root") {
                                                val newPath = pathParts.take(index + 1).joinToString("\\") + if(index == 0 && part.endsWith(":")) "\\" else ""
                                                pathStack.add(newPath)
                                                isLoading = true
                                                viewModel.browsePath(newPath)
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        part,
                                        color = if (isLast) primary else outline,
                                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                }
                                if (!isLast) {
                                    Icon(Icons.Default.ChevronRight, null, tint = outline.copy(0.5f), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        IconButton(onClick = { isGridView = !isGridView }) {
                            Icon(if (isGridView) Icons.Default.ViewList else Icons.Default.GridView, "Toggle View", tint = onBackground)
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.Sort, "Sort", tint = onBackground)
                            }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }, modifier = Modifier.background(surface)) {
                                SortOption.values().forEach { opt ->
                                    DropdownMenuItem(
                                        text = { Text(opt.name, color = if (sortOption == opt) primary else onBackground) },
                                        onClick = {
                                            if (sortOption == opt) sortAscending = !sortAscending else { sortOption = opt; sortAscending = true }
                                            showSortMenu = false
                                        },
                                        trailingIcon = {
                                            if (sortOption == opt) {
                                                Icon(if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, tint = primary, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (!isConnected) {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(Color(0xFFFF6B35).copy(0.15f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.WifiOff, null, tint = Color(0xFFFF6B35), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Not connected to PC", color = Color(0xFFFF6B35), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                AnimatedVisibility(transferProgress != null) {
                    transferProgress?.let { progress ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text("Downloading...", style = MaterialTheme.typography.bodySmall, color = primary)
                                Spacer(Modifier.weight(1f))
                                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = primary)
                            }
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = primary, trackColor = outlineVariant
                            )
                        }
                    }
                }

                // ── Clipboard Paste Bar ───────────────────────────────────
                AnimatedVisibility(clipboardItems.isNotEmpty() && !isSelectionMode) {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(primary.copy(0.1f)),
                        border = BorderStroke(1.dp, primary.copy(0.3f))
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (clipboardAction == "copy") Icons.Default.ContentCopy else Icons.Default.ContentCut, null, tint = primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${clipboardItems.size} items to ${clipboardAction}", color = onBackground, style = MaterialTheme.typography.bodyMedium)
                            }
                            IconButton(onClick = { clipboardItems = emptyList() }) {
                                Icon(Icons.Default.Close, "Cancel", tint = outline)
                            }
                            Button(
                                onClick = {
                                    val currentD = currentPath
                                    if (currentD != "root") {
                                        clipboardItems.forEach {
                                            if (clipboardAction == "copy") viewModel.copyFile(it.path, currentD)
                                            else viewModel.moveFile(it.path, currentD)
                                        }
                                        clipboardItems = emptyList()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(primary),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Text("Paste", color = Color.Black)
                            }
                        }
                    }
                }

                Box(Modifier.weight(1f).pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.size > 1) {
                                val zoomChange = event.calculateZoom()
                                zoomScale = (zoomScale * zoomChange).coerceIn(0.7f, 2.0f)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }) {
                    when {
                        isLoading -> {
                            Column(
                                Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(color = primary, modifier = Modifier.size(48.dp))
                                Spacer(Modifier.height(16.dp))
                                Text("Loading files from PC...", color = outline, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        loadError && fileList.isEmpty() -> {
                            Column(
                                Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.WifiOff, null, tint = Color.Gray, modifier = Modifier.size(64.dp))
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    if (!isConnected) "Connect to PC first" else "No response from PC",
                                    color = Color.Gray, style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("Make sure NexLink is running on your PC", color = outline, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(20.dp))
                                Button(
                                    onClick = { isLoading = true; loadError = false; viewModel.browsePath(pathStack.lastOrNull() ?: "root") },
                                    colors = ButtonDefaults.buttonColors(containerColor = primary),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, null, tint = Color.Black)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Retry", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        fileList.isEmpty() && !isLoading -> {
                            Column(
                                Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.FolderOpen, null, tint = Color.Gray, modifier = Modifier.size(64.dp))
                                Spacer(Modifier.height(12.dp))
                                Text("Empty Folder", color = Color.Gray, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        else -> {
                            if (isGridView) {
                                val cols = maxOf(2, (3f / zoomScale).toInt())
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(cols),
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(sortedList) { file ->
                                        FileGridItem(
                                            item = file,
                                            scale = zoomScale,
                                            isSelected = selectedItems.contains(file),
                                            isSelectionMode = isSelectionMode,
                                            onClick = {
                                                if (isSelectionMode) {
                                                    if (selectedItems.contains(file)) selectedItems.remove(file) else selectedItems.add(file)
                                                } else {
                                                    if (file.isDirectory) {
                                                        pathStack.add(file.path)
                                                        isLoading = true
                                                        viewModel.browsePath(file.path)
                                                    } else {
                                                        selectedFile = file
                                                        if (file.size < PREVIEW_SIZE_LIMIT) {
                                                            viewModel.requestFilePreview(file.path)
                                                            showPreviewSheet = true
                                                        } else showPreviewSheet = true
                                                    }
                                                }
                                            },
                                            onLongClick = {
                                                if (!isSelectionMode) selectedItems.add(file)
                                            },
                                            onMoreClick = {
                                                selectedFile = file
                                            }
                                        )
                                    }
                                }
                            } else {
                                LazyColumn(
                                    Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(sortedList) { file ->
                                        FileListItem(
                                            item = file,
                                            scale = zoomScale,
                                            isSelected = selectedItems.contains(file),
                                            isSelectionMode = isSelectionMode,
                                            onClick = {
                                                if (isSelectionMode) {
                                                    if (selectedItems.contains(file)) selectedItems.remove(file) else selectedItems.add(file)
                                                } else {
                                                    if (file.isDirectory) {
                                                        pathStack.add(file.path)
                                                        isLoading = true
                                                        viewModel.browsePath(file.path)
                                                    } else {
                                                        selectedFile = file
                                                        if (file.size < PREVIEW_SIZE_LIMIT) {
                                                            viewModel.requestFilePreview(file.path)
                                                            showPreviewSheet = true
                                                        } else showPreviewSheet = true
                                                    }
                                                }
                                            },
                                            onLongClick = {
                                                if (!isSelectionMode) selectedItems.add(file)
                                            },
                                            onMoreClick = {
                                                selectedFile = file
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Single Item Context Menu ───────────────────────────
        if (selectedFile != null && !showPreviewSheet && !isSelectionMode) {
            ModalBottomSheet(
                onDismissRequest = { selectedFile = null },
                containerColor = surface,
                sheetState = sheetState
            ) {
                Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    Text(selectedFile!!.name, style = MaterialTheme.typography.titleMedium, color = onBackground, modifier = Modifier.padding(16.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Divider(color = outlineVariant)
                    if (!selectedFile!!.isDirectory) {
                        ListItem(
                            headlineContent = { Text("Open / Preview", color = onBackground) },
                            leadingContent = { Icon(Icons.Default.Visibility, null, tint = primary) },
                            modifier = Modifier.clickable {
                                val f = selectedFile!!
                                selectedFile = null
                                selectedFile = f
                                if (f.size < PREVIEW_SIZE_LIMIT) viewModel.requestFilePreview(f.path)
                                showPreviewSheet = true
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        ListItem(
                            headlineContent = { Text("Open With...", color = onBackground) },
                            leadingContent = { Icon(Icons.Default.OpenInNew, null, tint = primary) },
                            modifier = Modifier.clickable {
                                val f = selectedFile!!
                                selectedFile = null
                                pendingOpenWith = f
                                viewModel.downloadFile(f.path)
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        ListItem(
                            headlineContent = { Text("Share", color = onBackground) },
                            leadingContent = { Icon(Icons.Default.Share, null, tint = primary) },
                            modifier = Modifier.clickable {
                                val f = selectedFile!!
                                selectedFile = null
                                pendingShare = f
                                viewModel.downloadFile(f.path)
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        ListItem(
                            headlineContent = { Text("Download", color = onBackground) },
                            leadingContent = { Icon(Icons.Default.Download, null, tint = primary) },
                            modifier = Modifier.clickable { viewModel.downloadFile(selectedFile!!.path); selectedFile = null },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                    ListItem(
                        headlineContent = { Text("Copy", color = onBackground) },
                        leadingContent = { Icon(Icons.Default.ContentCopy, null, tint = onBackground) },
                        modifier = Modifier.clickable { clipboardAction = "copy"; clipboardItems = listOf(selectedFile!!); selectedFile = null },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    ListItem(
                        headlineContent = { Text("Move", color = onBackground) },
                        leadingContent = { Icon(Icons.Default.ContentCut, null, tint = onBackground) },
                        modifier = Modifier.clickable { clipboardAction = "move"; clipboardItems = listOf(selectedFile!!); selectedFile = null },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    ListItem(
                        headlineContent = { Text("Rename", color = onBackground) },
                        leadingContent = { Icon(Icons.Default.Edit, null, tint = onBackground) },
                        modifier = Modifier.clickable { showRenameDialog = selectedFile; selectedFile = null },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    ListItem(
                        headlineContent = { Text("Delete", color = RedDisconnected) },
                        leadingContent = { Icon(Icons.Default.Delete, null, tint = RedDisconnected) },
                        modifier = Modifier.clickable { showDeleteDialog = listOf(selectedFile!!); selectedFile = null },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    ListItem(
                        headlineContent = { Text("Properties", color = onBackground) },
                        leadingContent = { Icon(Icons.Default.Info, null, tint = outline) },
                        modifier = Modifier.clickable { showPropertiesDialog = selectedFile; selectedFile = null },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
        }

        // ── Dialogs ──────────────────────────────────────────────
        if (showCreateDialog) {
            var name by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                containerColor = surface,
                title = { Text("Create ${createType.capitalize()}", color = onBackground) },
                text = {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            FilterChip(selected = createType == "folder", onClick = { createType = "folder" }, label = { Text("Folder") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = primary.copy(0.2f), selectedLabelColor = primary))
                            FilterChip(selected = createType == "file", onClick = { createType = "file" }, label = { Text("File") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = primary.copy(0.2f), selectedLabelColor = primary))
                        }
                        OutlinedTextField(
                            value = name, onValueChange = { name = it },
                            label = { Text("Name", color = outline) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primary, focusedTextColor = onBackground)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (name.isNotBlank()) {
                            if (createType == "folder") viewModel.createFolder(currentPath, name)
                            else viewModel.createFile(currentPath, name)
                            showCreateDialog = false
                        }
                    }) { Text("Create", color = primary) }
                },
                dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Cancel", color = outline) } }
            )
        }

        if (showRenameDialog != null) {
            var newName by remember { mutableStateOf(showRenameDialog!!.name) }
            AlertDialog(
                onDismissRequest = { showRenameDialog = null },
                containerColor = surface,
                title = { Text("Rename", color = onBackground) },
                text = {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        label = { Text("New Name", color = outline) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primary, focusedTextColor = onBackground)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newName.isNotBlank() && newName != showRenameDialog!!.name) {
                            viewModel.renameFile(showRenameDialog!!.path, newName)
                        }
                        showRenameDialog = null
                    }) { Text("Rename", color = primary) }
                },
                dismissButton = { TextButton(onClick = { showRenameDialog = null }) { Text("Cancel", color = outline) } }
            )
        }

        if (showDeleteDialog != null) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = null },
                containerColor = surface,
                title = { Text("Confirm Delete", color = onBackground) },
                text = { Text("Are you sure you want to delete ${showDeleteDialog!!.size} item(s)? This cannot be undone.", color = onBackground) },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteDialog!!.forEach { viewModel.deleteFile(it.path) }
                        showDeleteDialog = null
                        selectedItems.clear()
                    }) { Text("Delete", color = RedDisconnected) }
                },
                dismissButton = { TextButton(onClick = { showDeleteDialog = null }) { Text("Cancel", color = outline) } }
            )
        }

        if (showPropertiesDialog != null) {
            val f = showPropertiesDialog!!
            val dateStr = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(f.lastModified))
            AlertDialog(
                onDismissRequest = { showPropertiesDialog = null },
                containerColor = surface,
                title = { Text("Properties", color = onBackground) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Name: ${f.name}", color = onBackground, style = MaterialTheme.typography.bodyMedium)
                        Text("Type: ${if (f.isDirectory) "Folder" else f.type.uppercase()}", color = outline, style = MaterialTheme.typography.bodySmall)
                        Text("Path: ${f.path}", color = outline, style = MaterialTheme.typography.bodySmall)
                        if (!f.isDirectory) {
                            Text("Size: ${formatSize(f.size)}", color = outline, style = MaterialTheme.typography.bodySmall)
                        }
                        if (f.lastModified > 0) {
                            Text("Modified: $dateStr", color = outline, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showPropertiesDialog = null }) { Text("Close", color = primary) } }
            )
        }

        // ── Preview Bottom Sheet ───────────────────────────────────────────
        if (showPreviewSheet && selectedFile != null) {
            ModalBottomSheet(
                onDismissRequest = {
                    showPreviewSheet = false
                    viewModel.clearFilePreview()
                },
                sheetState = sheetState,
                modifier = Modifier.fillMaxHeight(0.95f),
                containerColor = Color(0xFF1A1A2E),
                dragHandle = { Box(Modifier.padding(vertical = 12.dp).size(40.dp, 4.dp).background(Color.White.copy(0.3f), RoundedCornerShape(2.dp))) }
            ) {
                FilePreviewContent(
                    file = selectedFile!!,
                    previewData = filePreviewData?.takeIf { it.first == selectedFile!!.path }?.second,
                    onDownload = { viewModel.downloadFile(selectedFile!!.path); showPreviewSheet = false },
                    onOpenWith = {
                        val f = selectedFile!!
                        showPreviewSheet = false
                        pendingOpenWith = f
                        viewModel.downloadFile(f.path)
                    },
                    onShare = {
                        val f = selectedFile!!
                        showPreviewSheet = false
                        pendingShare = f
                        viewModel.downloadFile(f.path)
                    },
                    onDismiss = { showPreviewSheet = false; viewModel.clearFilePreview() }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListItem(item: FileItem, scale: Float, isSelected: Boolean, isSelectionMode: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onMoreClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(if (isSelected) primary.copy(0.15f) else GlassSurface),
        border = BorderStroke(1.dp, if (isSelected) primary else GlassBorder)
    ) {
        Row(Modifier.padding(12.dp * scale), verticalAlignment = Alignment.CenterVertically) {
            if (isSelectionMode) {
                Checkbox(checked = isSelected, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = primary))
                Spacer(Modifier.width(8.dp))
            }
            Box(Modifier.size(48.dp * scale).background(fileIconColor(item).copy(0.12f), RoundedCornerShape(12.dp)), Alignment.Center) {
                if (item.thumbnailBase64 != null) {
                    val bmp = remember(item.thumbnailBase64) {
                        runCatching {
                            val bytes = Base64.decode(item.thumbnailBase64, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }.getOrNull()
                    }
                    if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                    else Icon(fileIcon(item), null, tint = fileIconColor(item), modifier = Modifier.size(24.dp * scale))
                } else Icon(fileIcon(item), null, tint = fileIconColor(item), modifier = Modifier.size(24.dp * scale))
            }
            Spacer(Modifier.width(14.dp * scale))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, color = onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        item.isDirectory && item.type == "drive" -> "Drive"
                        item.isDirectory -> "Folder"
                        else -> formatSize(item.size)
                    },
                    style = MaterialTheme.typography.bodySmall, color = outline
                )
            }
            if (!isSelectionMode) {
                IconButton(onClick = onMoreClick) { Icon(Icons.Default.MoreVert, "More", tint = outline) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridItem(item: FileItem, scale: Float, isSelected: Boolean, isSelectionMode: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onMoreClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(if (isSelected) primary.copy(0.15f) else GlassSurface),
        border = BorderStroke(1.dp, if (isSelected) primary else GlassBorder)
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(8.dp * scale), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(48.dp * scale).background(fileIconColor(item).copy(0.12f), RoundedCornerShape(12.dp)), Alignment.Center) {
                    if (item.thumbnailBase64 != null) {
                        val bmp = remember(item.thumbnailBase64) {
                            runCatching {
                                val bytes = Base64.decode(item.thumbnailBase64, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }.getOrNull()
                        }
                        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                        else Icon(fileIcon(item), null, tint = fileIconColor(item), modifier = Modifier.size(24.dp * scale))
                    } else Icon(fileIcon(item), null, tint = fileIconColor(item), modifier = Modifier.size(24.dp * scale))
                }
                Spacer(Modifier.height(8.dp * scale))
                Text(item.name, style = MaterialTheme.typography.bodySmall, color = onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            if (isSelectionMode) {
                Checkbox(checked = isSelected, onCheckedChange = null, modifier = Modifier.align(Alignment.TopStart), colors = CheckboxDefaults.colors(checkedColor = primary))
            } else {
                IconButton(onClick = onMoreClick, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) { Icon(Icons.Default.MoreVert, "More", tint = outline, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
private fun FilePreviewContent(file: FileItem, previewData: String?, onDownload: () -> Unit, onOpenWith: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(file.name, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(formatSize(file.size), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(0.5f))
        Spacer(Modifier.height(20.dp))

        if (file.size >= PREVIEW_SIZE_LIMIT) {
            Icon(Icons.Default.FileDownload, null, tint = primary, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(12.dp))
            Text("File is larger than 150 MB", color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodyMedium)
            Text("Download to view it", color = Color.White.copy(0.5f), style = MaterialTheme.typography.bodySmall)
        } else if (previewData == null) {
            CircularProgressIndicator(color = primary, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("Loading preview...", color = Color.White.copy(0.6f), style = MaterialTheme.typography.bodySmall)
        } else if (previewData.startsWith("image:")) {
            val b64 = previewData.removePrefix("image:")
            val bitmap = remember(b64) {
                runCatching {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), "Preview", Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Fit)
            }
        } else if (previewData.startsWith("text:")) {
            val b64 = previewData.removePrefix("text:")
            val text = remember(b64) {
                runCatching { String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) }.getOrElse { "Cannot decode text" }
            }
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black.copy(0.4f), RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text(text.take(4000), style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = Color(0xFF8FD96B), modifier = Modifier.verticalScroll(rememberScrollState()))
            }
        } else {
            Icon(fileIconFor(file.type), null, tint = primary, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(8.dp))
            Text("Preview not available", color = Color.White.copy(0.6f), style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onDownload,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = primary),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.FileDownload, null, tint = Color.Black)
            Spacer(Modifier.width(8.dp))
            Text("Download to Phone", color = Color.Black, fontWeight = FontWeight.Bold)
        }
        
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onOpenWith,
                modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = surface),
                border = BorderStroke(1.dp, primary),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.OpenInNew, null, tint = primary)
                Spacer(Modifier.width(8.dp))
                Text("Open", color = primary, fontWeight = FontWeight.Bold)
            }
            
            Button(
                onClick = onShare,
                modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = surface),
                border = BorderStroke(1.dp, primary),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Share, null, tint = primary)
                Spacer(Modifier.width(8.dp))
                Text("Share", color = primary, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close", color = Color.White.copy(0.5f)) }
        Spacer(Modifier.height(8.dp))
    }
}

private fun fileIcon(item: FileItem) = when {
    item.type == "drive" -> Icons.Default.Storage
    item.isDirectory     -> Icons.Default.Folder
    item.name.endsWith(".pdf", ignoreCase = true)  -> Icons.Default.PictureAsPdf
    item.name.endsWith(".mp4", ignoreCase = true) || item.name.endsWith(".mkv", ignoreCase = true) -> Icons.Default.VideoFile
    item.name.endsWith(".mp3", ignoreCase = true) || item.name.endsWith(".flac", ignoreCase = true) -> Icons.Default.AudioFile
    item.name.endsWith(".jpg", ignoreCase = true) || item.name.endsWith(".png", ignoreCase = true) || item.name.endsWith(".jpeg", ignoreCase = true) -> Icons.Default.Image
    item.name.endsWith(".zip", ignoreCase = true) || item.name.endsWith(".rar", ignoreCase = true) -> Icons.Default.FolderZip
    item.name.endsWith(".exe", ignoreCase = true) -> Icons.Default.Terminal
    item.name.endsWith(".apk", ignoreCase = true) -> Icons.Default.Android
    else -> Icons.Default.InsertDriveFile
}

private fun fileIconFor(type: String) = when (type) {
    "mp4", "mkv", "avi", "mov" -> Icons.Default.VideoFile
    "mp3", "flac", "wav", "aac" -> Icons.Default.AudioFile
    "pdf"                        -> Icons.Default.PictureAsPdf
    "zip", "rar"                 -> Icons.Default.FolderZip
    "exe"                        -> Icons.Default.Terminal
    else                         -> Icons.Default.InsertDriveFile
}

@Composable
private fun fileIconColor(item: FileItem) = when {
    item.type == "drive" -> secondary
    item.isDirectory     -> OrangeWarning
    item.name.endsWith(".pdf", ignoreCase = true)  -> RedDisconnected
    item.name.endsWith(".mp4", ignoreCase = true) || item.name.endsWith(".mkv", ignoreCase = true) -> primary
    item.name.endsWith(".mp3", ignoreCase = true) || item.name.endsWith(".flac", ignoreCase = true) -> GreenLight
    item.name.endsWith(".jpg", ignoreCase = true) || item.name.endsWith(".png", ignoreCase = true) -> secondary
    else -> Color.Gray
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024L            -> "$bytes B"
    bytes < 1024L * 1024     -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else                     -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024))
}
