package com.phynex.NexLink.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.phynex.NexLink.model.AppItem
import com.phynex.NexLink.model.PerformanceMetrics
import com.phynex.NexLink.ui.theme.*
import com.phynex.NexLink.viewmodel.MainViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppLauncherScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenFileBrowser: () -> Unit
) {
    val appList by viewModel.appList.collectAsState()
    val runningApps by viewModel.runningApps.collectAsState()
    val performance by viewModel.performance.collectAsState()
    var showAppPicker by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.requestRunningApps()
    }

    Box(Modifier.fillMaxSize().background(background)) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = onBackground) }
                Spacer(Modifier.weight(1f))
                Text("App Launcher", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = onBackground)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(48.dp))
            }

            if (runningApps.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = primary, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("Fetching running apps...", style = MaterialTheme.typography.bodyMedium, color = outline)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    // Performance Card
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PerformanceCard(performance)
                    }

                    val groupedApps = runningApps.groupBy { it.category.ifBlank { "Desktop 1" } }
                    
                    groupedApps.forEach { (category, apps) ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = category,
                                style = MaterialTheme.typography.titleMedium,
                                color = onBackground,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        
                        items(apps) { app ->
                            RunningAppItem(
                                app = app,
                                onOpen = { viewModel.focusApp(app.name, app.handle) },
                                onClose = { viewModel.closeApp(app.name, app.handle) }
                            )
                        }
                    }

                    item(span = { GridItemSpan(1) }) {
                        Column(
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.requestAppList()
                                showAppPicker = true
                            },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                Modifier.size(72.dp).background(primary.copy(0.12f), RoundedCornerShape(20.dp))
                                    .border(1.dp, GlassBorder, RoundedCornerShape(20.dp)),
                                Alignment.Center
                            ) {
                                Icon(Icons.Default.Add, null, tint = primary, modifier = Modifier.size(36.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Add App", 
                                style = MaterialTheme.typography.bodySmall, 
                                color = outline,
                                textAlign = TextAlign.Center, 
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // App picker sheet
        if (showAppPicker) {
            Box(Modifier.fillMaxSize().background(background.copy(0.95f)).clickable { showAppPicker = false })
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 40.dp, start = 20.dp, end = 20.dp, bottom = 20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showAppPicker = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = onBackground)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Launch Application", style = MaterialTheme.typography.titleLarge, color = onBackground, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = searchQuery, onValueChange = { searchQuery = it },
                    placeholder = { Text("Search apps...", color = outline) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = outline) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = primary, unfocusedBorderColor = GlassBorder,
                        focusedTextColor = onBackground, unfocusedTextColor = onBackground,
                        cursorColor = primary
                    )
                )
                Spacer(Modifier.height(16.dp))
                val filtered = appList
                    .filter { it.name.contains(searchQuery, ignoreCase = true) }
                    .sortedBy { it.name.lowercase() }
                    
                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
                        Text(if (appList.isEmpty()) "Loading apps from PC..." else "No apps found", color = outline)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filtered) { app ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    viewModel.launchApp(app.name, app.path)
                                    showAppPicker = false
                                }.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(40.dp).background(primary.copy(0.15f), RoundedCornerShape(10.dp)),
                                    Alignment.Center
                                ) {
                                    Icon(appIcon(app.name), null, tint = primary, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(app.name, style = MaterialTheme.typography.bodyMedium, color = onBackground)
                                Spacer(Modifier.weight(1f))
                                Icon(Icons.Default.PlayArrow, null, tint = outline, modifier = Modifier.size(20.dp))
                            }
                            HorizontalDivider(color = GlassBorder.copy(0.5f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PerformanceCard(perf: PerformanceMetrics) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(GlassSurface),
        border = BorderStroke(1.dp, GlassBorder)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Speed, contentDescription = null, tint = primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Performance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = onBackground)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PerfItem("CPU", if (perf.cpu >= 0) "${perf.cpu}%" else "--")
                PerfItem("GPU", if (perf.gpu >= 0) "${perf.gpu}%" else "--")
                PerfItem("VRAM", if (perf.vram >= 0) "${perf.vram}%" else "--")
                PerfItem("RAM", if (perf.ram > 0) "${perf.ram}%" else "--")
                PerfItem("FPS", if (perf.fps >= 0) "${perf.fps}" else "--")
            }
        }
    }
}

@Composable
fun PerfItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = outline)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = onBackground)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RunningAppItem(app: AppItem, onOpen: () -> Unit, onClose: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(72.dp)
                .background(GlassSurface, RoundedCornerShape(20.dp))
                .border(
                    width = if (app.isForeground) 2.dp else 1.dp,
                    color = if (app.isForeground) primary else GlassBorder,
                    shape = RoundedCornerShape(20.dp)
                )
                .combinedClickable(
                    onClick = { onOpen() },
                    onLongClick = { expanded = true }
                ),
            Alignment.Center
        ) {
            val iconBitmap = remember(app.iconBase64) {
                if (app.iconBase64.isNullOrBlank()) null
                else try {
                    val imageBytes = Base64.decode(app.iconBase64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)?.asImageBitmap()
                } catch (e: Exception) { null }
            }

            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = app.name,
                    modifier = Modifier.size(36.dp)
                )
            } else {
                Icon(appIcon(app.name), null, tint = primary, modifier = Modifier.size(36.dp))
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(GlassSurface)
            ) {
                DropdownMenuItem(
                    text = { Text("Bring to front", color = onBackground) },
                    onClick = {
                        onOpen()
                        expanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Close app", color = error) },
                    onClick = {
                        onClose()
                        expanded = false
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            app.name, 
            style = MaterialTheme.typography.bodySmall, 
            color = onSurfaceVariant,
            textAlign = TextAlign.Center, 
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun appIcon(name: String) = when {
    name.contains("chrome", true) || name.contains("brave", true) -> Icons.Default.Language
    name.contains("music", true) || name.contains("spotify", true) || name.contains("vlc", true) -> Icons.Default.MusicNote
    name.contains("file", true) || name.contains("explorer", true) -> Icons.Default.Folder
    name.contains("note", true) -> Icons.AutoMirrored.Filled.Notes
    name.contains("calc", true) -> Icons.Default.Calculate
    name.contains("settings", true) -> Icons.Default.Settings
    name.contains("photo", true) -> Icons.Default.Photo
    name.contains("whatsapp", true) -> Icons.AutoMirrored.Filled.Message
    else -> Icons.Default.Window
}


