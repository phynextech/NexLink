package com.phynex.NexLink.ui.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.phynex.NexLink.model.FileItem
import com.phynex.NexLink.ui.theme.*
import com.phynex.NexLink.viewmodel.MainViewModel
import kotlinx.coroutines.delay

@Composable
fun FileBrowserScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val fileList by viewModel.fileList.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()
    val transferProgress by viewModel.fileTransferProgress.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val pathStack = remember { mutableStateListOf("root") }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }

    // Load root on enter, with timeout detection
    LaunchedEffect(Unit) {
        isLoading = true
        loadError = false
        viewModel.browsePath("root")
        delay(5000) // wait up to 5s for response
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
                    Text(
                        "File Browser",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = onBackground
                    )
                    Text(
                        currentPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = outline,
                        maxLines = 1
                    )
                }
                // Refresh button
                IconButton(onClick = {
                    isLoading = true
                    loadError = false
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
                            color = primary,
                            trackColor = outlineVariant
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
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("Make sure NexLink is running on your PC", color = outline, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(20.dp))
                            Button(
                                onClick = {
                                    isLoading = true
                                    loadError = false
                                    viewModel.browsePath(pathStack.lastOrNull() ?: "root")
                                },
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
                                            viewModel.openFile(file.path)
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
    }
}

@Composable
private fun FileListItem(item: FileItem, onClick: () -> Unit, onDownload: (() -> Unit)?) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(GlassSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(fileIconColor(item).copy(0.15f), RoundedCornerShape(12.dp)),
                Alignment.Center
            ) {
                Icon(fileIcon(item), null, tint = fileIconColor(item), modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, color = onBackground, maxLines = 1)
                if (!item.isDirectory && item.size > 0) {
                    Text(formatSize(item.size), style = MaterialTheme.typography.bodySmall, color = outline)
                } else if (item.isDirectory) {
                    Text(
                        if (item.type == "drive") "Drive" else "Folder",
                        style = MaterialTheme.typography.bodySmall,
                        color = outline
                    )
                }
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
    item.isDirectory -> Icons.Default.Folder
    item.name.endsWith(".pdf", ignoreCase = true) -> Icons.Default.PictureAsPdf
    item.name.endsWith(".mp4", ignoreCase = true) || item.name.endsWith(".mkv", ignoreCase = true) -> Icons.Default.VideoFile
    item.name.endsWith(".mp3", ignoreCase = true) || item.name.endsWith(".flac", ignoreCase = true) -> Icons.Default.AudioFile
    item.name.endsWith(".jpg", ignoreCase = true) || item.name.endsWith(".png", ignoreCase = true) || item.name.endsWith(".jpeg", ignoreCase = true) -> Icons.Default.Image
    item.name.endsWith(".zip", ignoreCase = true) || item.name.endsWith(".rar", ignoreCase = true) -> Icons.Default.FolderZip
    item.name.endsWith(".exe", ignoreCase = true) -> Icons.Default.Terminal
    item.name.endsWith(".apk", ignoreCase = true) -> Icons.Default.Android
    else -> Icons.Default.InsertDriveFile
}

private fun fileIconColor(item: FileItem) = when {
    item.type == "drive" -> secondary
    item.isDirectory -> OrangeWarning
    item.name.endsWith(".pdf", ignoreCase = true) -> RedDisconnected
    item.name.endsWith(".mp4", ignoreCase = true) || item.name.endsWith(".mkv", ignoreCase = true) -> primary
    item.name.endsWith(".mp3", ignoreCase = true) || item.name.endsWith(".flac", ignoreCase = true) -> GreenLight
    item.name.endsWith(".jpg", ignoreCase = true) || item.name.endsWith(".png", ignoreCase = true) -> secondary
    else -> Color.Gray
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${bytes / (1024L * 1024 * 1024)} GB"
}
