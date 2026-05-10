package com.phynex.NexLink.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.phynex.NexLink.ui.theme.*
import com.phynex.NexLink.viewmodel.MainViewModel

import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.SurfaceViewRenderer

@Composable
fun CameraScreenPage(viewModel: MainViewModel, onBack: () -> Unit) {
    val isConnected by viewModel.isConnected.collectAsState()
    val isStreamingScreen by viewModel.isStreamingScreen.collectAsState()
    val isStreamingCamera by viewModel.isStreamingCamera.collectAsState()
    val isPiP by (com.phynex.NexLink.MainActivity.instance?.isPiPMode?.collectAsState() ?: mutableStateOf(false))

    var isFullScreenScreen by remember { mutableStateOf(false) }
    var isFullScreenCamera by remember { mutableStateOf(false) }

    // State for zoom
    var screenScale by remember { mutableFloatStateOf(1f) }
    var screenOffset by remember { mutableStateOf<Offset>(Offset.Zero) }

    var showMicDialog by remember { mutableStateOf(false) }

    if (showMicDialog) {
        AlertDialog(
            onDismissRequest = { showMicDialog = false },
            containerColor = Color(0xFF1A1A2E),
            title = { Text("Enable Audio?", color = Color.White) },
            text = { Text("Do you want to listen to the PC's microphone/audio along with the camera feed?", color = onBackground) },
            confirmButton = {
                TextButton(onClick = {
                    showMicDialog = false
                    viewModel.startCameraStream(enableMic = true)
                }) { Text("YES", color = primary) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showMicDialog = false
                    viewModel.startCameraStream(enableMic = false)
                }) { Text("NO", color = Color.Gray) }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            if (isStreamingScreen) viewModel.stopScreenStream()
            if (isStreamingCamera) viewModel.stopCameraStream()
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0F))) {
        if (isFullScreenScreen || isFullScreenCamera) {
            val activeTitle = if (isFullScreenScreen) "Screen Mirror" else "Camera Feed"

            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (activeTitle == "Screen Mirror" && isStreamingScreen || activeTitle == "Camera Feed" && isStreamingCamera) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).apply {
                                setZOrderMediaOverlay(true)
                                viewModel.webRtcManager.setRemoteRenderer(this)
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    screenScale = (screenScale * zoom).coerceIn(1f, 5f)
                                    screenOffset = screenOffset + pan
                                }
                            }
                            .graphicsLayer(
                                scaleX = screenScale,
                                scaleY = screenScale,
                                translationX = screenOffset.x,
                                translationY = screenOffset.y
                            )
                    )
                } else {
                    Text("Loading Stream...", color = Color.White, modifier = Modifier.align(Alignment.Center))
                }

                // Controls overlay
                Row(
                    Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopStart),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        isFullScreenScreen = false
                        isFullScreenCamera = false
                        screenScale = 1f
                        screenOffset = Offset.Zero
                    }, modifier = Modifier.background(Color.Black.copy(0.4f), CircleShape)) {
                        Icon(Icons.Default.Close, null, tint = Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(activeTitle, color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        com.phynex.NexLink.MainActivity.instance?.enterPiP()
                    }, modifier = Modifier.background(Color.Black.copy(0.4f), CircleShape)) {
                        Icon(Icons.Default.PictureInPicture, null, tint = Color.White)
                    }
                }
            }
        } else {
            // Standard Split View
            Column(Modifier.fillMaxSize()) {
                // Top Bar
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                    Text("Live Dashboard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { com.phynex.NexLink.MainActivity.instance?.enterPiP() }) {
                        Icon(Icons.Default.PictureInPicture, null, tint = primary)
                    }
                }

                // 1st Half: Screen Share
                StreamCard(
                    title = "SCREEN SHARE",
                    icon = Icons.Default.DesktopWindows,
                    isStreaming = isStreamingScreen,
                    viewModel = viewModel,
                    onStart = { viewModel.startScreenStream() },
                    onStop = { viewModel.stopScreenStream() },
                    onExpand = { isFullScreenScreen = true },
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
                )

                Spacer(Modifier.height(16.dp))

                // 2nd Half: Camera
                StreamCard(
                    title = "PC CAMERA",
                    icon = Icons.Default.Videocam,
                    isStreaming = isStreamingCamera,
                    viewModel = viewModel,
                    onStart = { showMicDialog = true },
                    onStop = { viewModel.stopCameraStream() },
                    onExpand = { isFullScreenCamera = true },
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun StreamCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isStreaming: Boolean,
    viewModel: MainViewModel,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111122)),
        border = BorderStroke(1.dp, Color.White.copy(0.05f))
    ) {
        Box(Modifier.fillMaxSize()) {
            if (isStreaming) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            setZOrderMediaOverlay(true)
                            viewModel.webRtcManager.setRemoteRenderer(this)
                        }
                    },
                    modifier = Modifier.fillMaxSize().clickable { onExpand() }
                )
                // Overlay info
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.7f)))))
                
                Row(
                    Modifier.align(Alignment.BottomStart).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF00C853)))
                    Spacer(Modifier.width(8.dp))
                    Text(title, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }

                IconButton(
                    onClick = onStop,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Black.copy(0.4f), CircleShape)
                ) {
                    Icon(Icons.Default.Stop, null, tint = Color.White)
                }
                
                IconButton(
                    onClick = onExpand,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(primary, CircleShape)
                ) {
                    Icon(Icons.Default.Fullscreen, null, tint = Color.Black)
                }
            } else {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(icon, null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(title, color = Color.Gray, style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp)
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onStart,
                        colors = ButtonDefaults.buttonColors(containerColor = primary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("START", color = Color.Black, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}
