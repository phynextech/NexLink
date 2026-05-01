package com.phynex.NexLink.ui.screens

import android.Manifest
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.google.accompanist.permissions.*
import com.phynex.NexLink.ui.theme.*
import com.phynex.NexLink.viewmodel.MainViewModel

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreenPage(viewModel: MainViewModel, onBack: () -> Unit) {
    val isConnected by viewModel.isConnected.collectAsState()
    val isStreamingScreen by viewModel.isStreamingScreen.collectAsState()
    val isStreamingCamera by viewModel.isStreamingCamera.collectAsState()
    val screenFrameB64 by viewModel.screenFrameBase64.collectAsState()
    val cameraFrameB64 by viewModel.cameraFrameBase64.collectAsState()

    var activeTab by remember { mutableStateOf(0) } // 0=screen, 1=camera

    // Decode current frame to bitmap
    val currentBitmap = remember(screenFrameB64, cameraFrameB64, activeTab) {
        val data = if (activeTab == 0) screenFrameB64 else cameraFrameB64
        data?.let {
            try {
                val bytes = Base64.decode(it, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (_: Exception) { null }
        }
    }

    // Auto-stop streaming on exit
    DisposableEffect(Unit) {
        onDispose {
            if (isStreamingScreen) viewModel.stopScreenStream()
            if (isStreamingCamera) viewModel.stopCameraStream()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(Modifier.fillMaxSize()) {

            // ── Top bar ──────────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (isStreamingScreen) viewModel.stopScreenStream()
                    if (isStreamingCamera) viewModel.stopCameraStream()
                    onBack()
                }) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (activeTab == 0) "Screen Share" else "PC Camera",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.weight(1f))
                // Connection badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isConnected) Color(0xFF00C853).copy(0.2f) else Color.Red.copy(0.2f)
                ) {
                    Text(
                        if (isConnected) "● Connected" else "● Disconnected",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = if (isConnected) Color(0xFF00C853) else Color.Red,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ── Tab selector ─────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .background(Color(0xFF1A1A2E), RoundedCornerShape(16.dp))
                    .padding(4.dp)
            ) {
                listOf("🖥  Screen", "📷  Camera").forEachIndexed { idx, label ->
                    val selected = activeTab == idx
                    Box(
                        Modifier
                            .weight(1f)
                            .background(
                                if (selected) primary else Color.Transparent,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                // Stop other stream if running
                                if (idx == 0 && isStreamingCamera) viewModel.stopCameraStream()
                                if (idx == 1 && isStreamingScreen) viewModel.stopScreenStream()
                                activeTab = idx
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (selected) Color.Black else Color.Gray,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Stream viewer ─────────────────────────────────────────
            val isStreaming = if (activeTab == 0) isStreamingScreen else isStreamingCamera

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .background(Color(0xFF111122), RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (currentBitmap != null) {
                    Image(
                        bitmap = currentBitmap,
                        contentDescription = "Stream",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            if (activeTab == 0) Icons.Default.DesktopWindows else Icons.Default.Videocam,
                            null,
                            tint = Color.Gray,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (!isConnected) "Not connected to PC"
                            else if (isStreaming) "Starting stream..."
                            else if (activeTab == 0) "Click Start to share PC screen"
                            else "Click Start to view PC camera",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // FPS / quality indicator when streaming
                if (isStreaming && currentBitmap != null) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(0.6f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("● LIVE", color = Color(0xFF00C853), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Control buttons ───────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!isStreaming) {
                    Button(
                        onClick = {
                            if (!isConnected) return@Button
                            if (activeTab == 0) viewModel.startScreenStream()
                            else viewModel.startCameraStream()
                        },
                        modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primary),
                        shape = RoundedCornerShape(14.dp),
                        enabled = isConnected
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = Color.Black)
                        Spacer(Modifier.width(8.dp))
                        Text("Start Stream", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = {
                            if (activeTab == 0) viewModel.stopScreenStream()
                            else viewModel.stopCameraStream()
                        },
                        modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Stop, null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop Stream", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
