package com.phynex.NexLink.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.*
import com.phynex.NexLink.ui.theme.*
import com.phynex.NexLink.viewmodel.MainViewModel
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToMusic: () -> Unit,
    onNavigateToAppLauncher: () -> Unit,
    onNavigateToClipboard: () -> Unit,
    onNavigateToCameraScreen: () -> Unit,
    onNavigateToFileBrowser: () -> Unit
) {
    val isConnected by viewModel.isConnected.collectAsState()
    val deviceInfo by viewModel.connectedDevice.collectAsState()
    val wifiInfo by viewModel.wifiInfo.collectAsState()
    val batteryInfo by viewModel.batteryInfo.collectAsState()
    val wallpaperBase64 by viewModel.wallpaperBase64.collectAsState()
    val bluetoothDevices by viewModel.bluetoothDevices.collectAsState()
    val bluetoothEnabled by viewModel.bluetoothEnabled.collectAsState()
    val volume by viewModel.volume.collectAsState()
    val brightness by viewModel.brightness.collectAsState()
    val muted by viewModel.muted.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val osVersion by viewModel.osVersion.collectAsState()
    val volumeOsd by viewModel.volumeOsdTrigger.collectAsState()
    val brightnessOsd by viewModel.brightnessOsdTrigger.collectAsState()

    // OSD visibility
    var osdLabel by remember { mutableStateOf<String?>(null) }
    var osdIcon by remember { mutableStateOf("") }

    LaunchedEffect(volumeOsd) {
        volumeOsd ?: return@LaunchedEffect
        osdIcon = "🔊"; osdLabel = "Volume: $volumeOsd%"
        delay(2000); osdLabel = null
    }
    LaunchedEffect(brightnessOsd) {
        brightnessOsd ?: return@LaunchedEffect
        osdIcon = "☀"; osdLabel = "Brightness: $brightnessOsd%"
        delay(2000); osdLabel = null
    }

    Scaffold(
        containerColor = background,
        bottomBar = {
            BottomNavigationBar(onNavigateToMusic, onNavigateToFileBrowser, onNavigateToAppLauncher)
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(Modifier.height(32.dp))

                // Top Header
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Sensors, null, tint = primary, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("NexLink", style = MaterialTheme.typography.headlineLarge, color = primary, fontWeight = FontWeight.Black)
                    }
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).border(1.dp, primary.copy(alpha = 0.3f), CircleShape)
                    ) {
                        Icon(Icons.Default.Person, null, tint = primary, modifier = Modifier.align(Alignment.Center))
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Device Info
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(deviceName, style = MaterialTheme.typography.titleLarge, color = primary, fontWeight = FontWeight.Bold)
                    val connectionMode by viewModel.connectionMode.collectAsState()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (isConnected) GreenLight else RedDisconnected))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isConnected) "CONNECTED • $connectionMode" else connectionMode.uppercase(),
                            style = MaterialTheme.typography.labelSmall, color = outline
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                // WiFi + BT Pills
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Row(
                        Modifier.glassCard(RoundedCornerShape(50.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Wifi, null, tint = primary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(wifiInfo?.ssid?.ifEmpty { "Not Connected" } ?: "—", style = MaterialTheme.typography.labelMedium, color = onBackground)
                        Spacer(Modifier.width(16.dp))
                        Box(Modifier.size(4.dp).clip(CircleShape).background(outlineVariant))
                        Spacer(Modifier.width(16.dp))
                        Icon(
                            Icons.Default.Bluetooth, null,
                            tint = if (bluetoothEnabled) primary else outline,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            bluetoothDevices.firstOrNull()?.name ?: if (bluetoothEnabled) "On" else "Off",
                            style = MaterialTheme.typography.labelMedium, color = onBackground
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Wallpaper Card
                Box(
                    Modifier.fillMaxWidth().height(200.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
                ) {
                    wallpaperBase64?.let { b64 ->
                        runCatching {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }.getOrNull()?.let { bmp ->
                            Image(bmp.asImageBitmap(), "Wallpaper", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    } ?: Box(Modifier.fillMaxSize().background(
                        Brush.linearGradient(listOf(Color(0xFF1A1A2E), Color(0xFF0D0D1A)))
                    ))

                    // Windows card
                    Box(
                        Modifier.fillMaxSize()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, background.copy(0.85f))))
                    )
                    Row(Modifier.align(Alignment.BottomStart).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).glassCard(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.GridView, null, tint = Color.White)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(deviceName, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                            Text(osVersion, style = MaterialTheme.typography.bodySmall, color = onBackground.copy(0.7f))
                        }
                    }
                    Icon(Icons.Default.Cast, null, tint = primary.copy(0.8f), modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
                }

                Spacer(Modifier.height(24.dp))

                // System Status Bar
                Row(
                    Modifier.fillMaxWidth().glassCard().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatusItem(Icons.Default.Wifi, wifiInfo?.ssid?.ifEmpty { "—" } ?: "—")

                    // Battery with charging indicator
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (batteryInfo?.isCharging == true) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
                            null,
                            tint = if (batteryInfo?.isCharging == true) GreenLight else primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${batteryInfo?.level ?: 0}%${if (batteryInfo?.isCharging == true) " ⚡" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = onBackground.copy(0.8f),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    StatusItem(
                        if (bluetoothEnabled) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                        bluetoothDevices.firstOrNull()?.name ?: if (bluetoothEnabled) "On" else "Off"
                    )
                    StatusItem(Icons.Default.VolumeUp, "${volume}%")
                }

                Spacer(Modifier.height(24.dp))

                // Volume & Brightness sliders
                // Volume
                VolumeBrightnessControl(
                    icon = if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeDown,
                    label = if (muted) "Master Volume (Muted)" else "Master Volume",
                    value = volume
                ) { viewModel.sendVolume(it) }
                Spacer(Modifier.height(16.dp))
                VolumeBrightnessControl(Icons.Default.LightMode, "Screen Brightness", brightness) { viewModel.sendBrightness(it) }

                Spacer(Modifier.height(24.dp))

                // Quick Actions
                Text("QUICK ACTIONS", style = MaterialTheme.typography.labelSmall, color = outline, letterSpacing = 2.sp)
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    QuickAction(Icons.Default.Lock, "Lock") { viewModel.lockPC() }
                    QuickAction(Icons.Default.MusicNote, "Music", onNavigateToMusic)
                    QuickAction(Icons.Default.PhotoCamera, "Camera", onNavigateToCameraScreen)
                    QuickAction(Icons.Default.ContentPaste, "Clip", onNavigateToClipboard)
                    QuickAction(Icons.Default.MoreHoriz, "More", onNavigateToAppLauncher)
                }

                Spacer(Modifier.height(48.dp))
            }

            // ── Phone OSD overlay (bottom center) ───────────────────────
            AnimatedVisibility(
                visible = osdLabel != null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit  = fadeOut() + slideOutVertically(targetOffsetY  = { it / 2 }),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)
            ) {
                osdLabel?.let { label ->
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = Color(0xDD111122),
                        border = BorderStroke(1.dp, primary.copy(0.3f)),
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(osdIcon, style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.width(12.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = onBackground.copy(0.8f), fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun VolumeBrightnessControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth().glassCard().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = primary)
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Text("$value", style = MaterialTheme.typography.titleLarge, color = primary, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = primary, inactiveTrackColor = background)
        )
    }
}

@Composable
fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(Modifier.size(64.dp).glassCard(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = primary, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = outline, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun BottomNavigationBar(onMusic: () -> Unit, onFiles: () -> Unit, onApps: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(background.copy(0.8f))
            .border(1.dp, GlassBorder, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .padding(vertical = 16.dp, horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        BottomNavItem(Icons.Default.Dashboard, "Home", true) {}
        BottomNavItem(Icons.Default.FolderOpen, "Files", false, onFiles)
        BottomNavItem(Icons.Default.PlayCircle, "Media", false, onMusic)
        BottomNavItem(Icons.Default.Apps, "Apps", false, onApps)
    }
}

@Composable
fun BottomNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).run {
            if (selected) background(primary.copy(0.1f), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 8.dp)
            else padding(horizontal = 16.dp, vertical = 8.dp)
        }
    ) {
        Icon(icon, null, tint = if (selected) primary else outline)
        Spacer(Modifier.height(4.dp))
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (selected) primary else outline)
    }
}
