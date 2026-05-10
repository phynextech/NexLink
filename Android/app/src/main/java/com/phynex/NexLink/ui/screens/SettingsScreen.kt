package com.phynex.NexLink.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phynex.NexLink.ui.theme.*
import com.phynex.NexLink.viewmodel.MainViewModel

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onNavigateToQrScanner: () -> Unit
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val primaryColor by viewModel.primaryColor.collectAsState()

    // Mock list of trusted devices / PCs
    val trustedDevices = listOf(
        "Main Desktop (Online)",
        "Work Laptop (Offline)"
    )

    Scaffold(
        containerColor = background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = onBackground)
                }
                Spacer(Modifier.width(8.dp))
                Text("Settings", style = MaterialTheme.typography.titleLarge, color = onBackground, fontWeight = FontWeight.Bold)
            }
        }
    ) { paddingVals ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingVals)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            // ── Account Switcher / Trusted Devices ──────────────────────
            Text("ACCOUNT & DEVICES", style = MaterialTheme.typography.labelMedium, color = outline, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            Column(Modifier.fillMaxWidth().glassCard(RoundedCornerShape(16.dp)).padding(16.dp)) {
                trustedDevices.forEachIndexed { index, device ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(40.dp).background(primary.copy(0.1f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Computer, null, tint = primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(device, style = MaterialTheme.typography.bodyLarge, color = onBackground, fontWeight = FontWeight.SemiBold)
                            Text(if (index == 0) "Current Device" else "Tap to switch", style = MaterialTheme.typography.bodySmall, color = outline)
                        }
                        if (index == 0) {
                            Icon(Icons.Default.CheckCircle, null, tint = primary, modifier = Modifier.size(20.dp))
                        }
                    }
                    if (index < trustedDevices.lastIndex) {
                        Divider(color = outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 12.dp))
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = onNavigateToQrScanner,
                    colors = ButtonDefaults.buttonColors(containerColor = primary.copy(0.15f), contentColor = primary),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.QrCodeScanner, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Pair New Device", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(32.dp))

            // ── Theme Settings ──────────────────────────────────────
            Text("APPEARANCE", style = MaterialTheme.typography.labelMedium, color = outline, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            Column(Modifier.fillMaxWidth().glassCard(RoundedCornerShape(16.dp)).padding(16.dp)) {
                Text("Theme Mode", style = MaterialTheme.typography.bodyMedium, color = onBackground, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("System", "Light", "Dark").forEach { mode ->
                        Button(
                            onClick = { viewModel.setThemeMode(mode) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (themeMode == mode) primary else Color.Transparent,
                                contentColor = if (themeMode == mode) onPrimary else onBackground
                            ),
                            border = BorderStroke(1.dp, if (themeMode == mode) Color.Transparent else outlineVariant),
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(mode, maxLines = 1, fontWeight = if (themeMode == mode) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                Text("Accent Color", style = MaterialTheme.typography.bodyMedium, color = onBackground, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                val colors = listOf("Monochrome", "Green", "Pink", "Lavender", "Orange", "Blue", "Red")
                
                // Color dots layout
                val chunkedColors = colors.chunked(4)
                chunkedColors.forEach { rowColors ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowColors.forEach { colorName ->
                            val isSelected = primaryColor == colorName
                            val actualColor = when (colorName) {
                                "Monochrome" -> Color(0xFFE2E2E2)
                                "Green" -> Color(0xFF00E676)
                                "Pink" -> Color(0xFFFF4081)
                                "Lavender" -> Color(0xFFB388FF)
                                "Orange" -> Color(0xFFFF6D00)
                                "Blue" -> Color(0xFF2979FF)
                                "Red" -> Color(0xFFFF1744)
                                else -> Color(0xFFE2E2E2)
                            }
                            
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(CircleShape)
                                    .background(actualColor)
                                    .border(2.dp, if (isSelected) Color.White else Color.Transparent, CircleShape)
                                    .clickable { viewModel.setPrimaryColor(colorName) },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(Icons.Default.Check, null, tint = if (colorName == "Monochrome") Color.Black else Color.White)
                                }
                            }
                        }
                        // Fill empty spots if less than 4 items
                        for (i in 0 until (4 - rowColors.size)) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}
