package com.phynex.NexLink.ui.screens

import androidx.compose.animation.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.phynex.NexLink.model.AppItem
import com.phynex.NexLink.ui.theme.*
import com.phynex.NexLink.viewmodel.MainViewModel

@Composable
fun AppLauncherScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenFileBrowser: () -> Unit
) {
    val appList by viewModel.appList.collectAsState()
    val pinnedApps by viewModel.pinnedApps.collectAsState()
    var showAppPicker by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize().background(background)) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = onBackground) }
                Spacer(Modifier.weight(1f))
                Text("App Launcher", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = onBackground)
                Spacer(Modifier.weight(1f))
                IconButton({
                    viewModel.requestAppList()
                    showAppPicker = true
                }) {
                    Icon(Icons.Default.Add, "Add App", tint = primary)
                }
            }

            // Pinned apps grid
            if (pinnedApps.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Apps, null, tint = outline, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No apps pinned yet", style = MaterialTheme.typography.bodyLarge, color = outline)
                        Spacer(Modifier.height(8.dp))
                        Text("Tap + to add apps from your PC", style = MaterialTheme.typography.bodySmall, color = outline)
                        Spacer(Modifier.height(24.dp))
                        // File Manager always visible
                        AppGridItem(AppItem("File Manager", "explorer.exe")) {
                            viewModel.launchApp("File Manager", "explorer.exe")
                            onOpenFileBrowser()
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // Always show File Manager first
                    item {
                        AppGridItem(AppItem("Files", "explorer.exe")) {
                            viewModel.launchApp("File Manager", "explorer.exe")
                            onOpenFileBrowser()
                        }
                    }
                    items(pinnedApps) { app ->
                        AppGridItem(app) {
                            if (app.name == "File Manager") onOpenFileBrowser()
                            else viewModel.launchApp(app.name, app.path)
                        }
                    }
                }
            }
        }

        // App picker sheet
        if (showAppPicker) {
            Box(Modifier.fillMaxSize().background(background.copy(0.8f)).clickable { showAppPicker = false })
            Card(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 500.dp),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                colors = CardDefaults.cardColors(GlassSurface)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Windows Apps", style = MaterialTheme.typography.titleLarge, color = onBackground, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
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
                    Spacer(Modifier.height(12.dp))
                    val filtered = appList.filter { it.name.contains(searchQuery, ignoreCase = true) }
                    if (filtered.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(100.dp), Alignment.Center) {
                            Text(if (appList.isEmpty()) "Loading apps from PC..." else "No apps found", color = outline)
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(filtered) { app ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        viewModel.pinApp(app)
                                        showAppPicker = false
                                    }.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier.size(40.dp).background(primary.copy(0.15f), RoundedCornerShape(10.dp)),
                                        Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Window, null, tint = primary, modifier = Modifier.size(22.dp))
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(app.name, style = MaterialTheme.typography.bodyMedium, color = onBackground)
                                    Spacer(Modifier.weight(1f))
                                    Icon(Icons.Default.Add, null, tint = outline, modifier = Modifier.size(18.dp))
                                }
                                HorizontalDivider(color = GlassBorder.copy(0.5f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppGridItem(app: AppItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(60.dp).background(primary.copy(0.12f), RoundedCornerShape(16.dp))
                .border(1.dp, GlassBorder, RoundedCornerShape(16.dp)),
            Alignment.Center
        ) {
            Icon(appIcon(app.name), null, tint = primary, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            app.name, style = MaterialTheme.typography.bodySmall, color = onSurfaceVariant,
            textAlign = TextAlign.Center, maxLines = 2
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

