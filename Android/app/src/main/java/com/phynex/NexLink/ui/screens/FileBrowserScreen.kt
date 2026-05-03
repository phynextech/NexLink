package com.phynex.NexLink.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.phynex.NexLink.model.FileItem
import com.phynex.NexLink.ui.theme.*
import com.phynex.NexLink.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// File size threshold for inline preview: 150 MB
private const val PREVIEW_SIZE_LIMIT = 150L * 1024 * 1024

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

    // Load root on enter, with 15s timeout
    LaunchedEffect(Unit) {
        isLoading = true
        loadError = false
        viewModel.browsePath("root")
        delay(15_000)
        if (isLoading && fileList.isEmpty()) {
            loadError = true
            isLoading = false
        }
    }

    // When fileList changes, stop loading
    LaunchedEffect(fileList) {
        if (fileList.isNotEmpty() || currentPath != "root") {
            isLoading = false
            loadError = false
        }
    }

    // 2-second sync loop for current folder
    LaunchedEffect(currentPath) {
        while (isActive) {
            delay(2_000)
            if (!isLoading && isConnected)
                viewModel.browsePath(pathStack.lastOrNull() ?: "root")
        }
    }

    // Preview sheet state
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(Modifier.fillMaxSize().background(background)) {
        Column(Modifier.fillMaxSize()) {

            // ── Top bar ───────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                Column(Modifier.weight(1f)) {
                    Text("File Browser", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = onBackground)
                    Text(
                        pathStack.lastOrNull()?.substringAfterLast("\\") ?: "root",
                        style = MaterialTheme.typography.bodySmall, color = outline, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = {
                    isLoading = true; loadError = false
                    viewModel.browsePath(pathStack.lastOrNull() ?: "root")
                }) {
                    Icon(Icons.Default.Refresh, "Refresh", tint = primary)
                }
            }

            // ── Connection warning ────────────────────────────────────
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

            // ── Transfer progress ─────────────────────────────────────
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

            // ── Content area ──────────────────────────────────────────
            Box(Modifier.weight(1f)) {
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
                    loadError || (fileList.isEmpty() && !isLoading) -> {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.FolderOff, null, tint = Color.Gray, modifier = Modifier.size(64.dp))
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
                    else -> {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(fileList) { file ->
                                FileListItem(
                                    item = file,
                                    onClick = {
                                        if (file.isDirectory) {
                                            pathStack.add(file.path)
                                            isLoading = true
                                            viewModel.browsePath(file.path)
                                        } else {
                                            selectedFile = file
                                            if (file.size < PREVIEW_SIZE_LIMIT) {
                                                // Request inline preview
                                                viewModel.requestFilePreview(file.path)
                                                showPreviewSheet = true
                                            } else {
                                                // Large file: offer download
                                                showPreviewSheet = true
                                            }
                                        }
                                    },
                                    onDownload = if (!file.isDirectory) ({ viewModel.downloadFile(file.path) }) else null
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Preview Bottom Sheet ───────────────────────────────────────────
        if (showPreviewSheet && selectedFile != null) {
            ModalBottomSheet(
                onDismissRequest = {
                    showPreviewSheet = false
                    viewModel.clearFilePreview()
                },
                sheetState = sheetState,
                containerColor = Color(0xFF1A1A2E),
                dragHandle = {
                    Box(Modifier.padding(vertical = 12.dp).size(40.dp, 4.dp).background(Color.White.copy(0.3f), RoundedCornerShape(2.dp)))
                }
            ) {
                FilePreviewContent(
                    file = selectedFile!!,
                    previewData = filePreviewData?.takeIf { it.first == selectedFile!!.path }?.second,
                    onDownload = {
                        viewModel.downloadFile(selectedFile!!.path)
                        showPreviewSheet = false
                    },
                    onDismiss = {
                        showPreviewSheet = false
                        viewModel.clearFilePreview()
                    }
                )
            }
        }
    }
}

@Composable
private fun FilePreviewContent(
    file: FileItem,
    previewData: String?,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // File name
        Text(file.name, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(formatSize(file.size), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(0.5f))
        Spacer(Modifier.height(20.dp))

        if (file.size >= PREVIEW_SIZE_LIMIT) {
            // Large file — just show download prompt
            Icon(Icons.Default.FileDownload, null, tint = primary, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(12.dp))
            Text("File is larger than 150 MB", color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodyMedium)
            Text("Download to view it", color = Color.White.copy(0.5f), style = MaterialTheme.typography.bodySmall)
        } else if (previewData == null) {
            // Waiting for preview
            CircularProgressIndicator(color = primary, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("Loading preview...", color = Color.White.copy(0.6f), style = MaterialTheme.typography.bodySmall)
        } else if (previewData.startsWith("image:")) {
            // Image preview
            val b64 = previewData.removePrefix("image:")
            val bitmap = remember(b64) {
                runCatching {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
            if (bitmap != null) {
                Image(
                    bitmap.asImageBitmap(), "Preview",
                    Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit
                )
            }
        } else if (previewData.startsWith("text:")) {
            // Text preview
            val b64 = previewData.removePrefix("text:")
            val text = remember(b64) {
                runCatching { String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) }.getOrElse { "Cannot decode text" }
            }
            Box(
                Modifier.fillMaxWidth().heightIn(max = 300.dp)
                    .background(Color.Black.copy(0.4f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text.take(2000),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = Color(0xFF8FD96B),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }
        } else {
            // Media / unsupported
            Icon(fileIconFor(file.type), null, tint = primary, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(8.dp))
            Text("Preview not available", color = Color.White.copy(0.6f), style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(24.dp))

        // Download button
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

        Spacer(Modifier.height(8.dp))

        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("Close", color = Color.White.copy(0.5f))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FileListItem(item: FileItem, onClick: () -> Unit, onDownload: (() -> Unit)?) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(GlassSurface),
        border = BorderStroke(1.dp, GlassBorder)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Thumbnail or icon
            Box(
                Modifier.size(48.dp).background(fileIconColor(item).copy(0.12f), RoundedCornerShape(12.dp)),
                Alignment.Center
            ) {
                if (item.thumbnailBase64 != null) {
                    val bmp = remember(item.thumbnailBase64) {
                        runCatching {
                            val bytes = Base64.decode(item.thumbnailBase64, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }.getOrNull()
                    }
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(), null,
                            Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(fileIcon(item), null, tint = fileIconColor(item), modifier = Modifier.size(24.dp))
                    }
                } else {
                    Icon(fileIcon(item), null, tint = fileIconColor(item), modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
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
            if (item.isDirectory) {
                Icon(Icons.Default.ChevronRight, null, tint = outline, modifier = Modifier.size(20.dp))
            } else {
                IconButton(onClick = { onDownload?.invoke() }) {
                    Icon(Icons.Default.Download, "Download", tint = primary)
                }
            }
        }
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
    else                     -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
}
