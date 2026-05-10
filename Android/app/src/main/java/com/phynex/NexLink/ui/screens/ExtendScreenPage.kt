package com.phynex.NexLink.ui.screens

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
import com.phynex.NexLink.ui.theme.*
import com.phynex.NexLink.viewmodel.MainViewModel

@Composable
fun ExtendScreenPage(viewModel: MainViewModel, onBack: () -> Unit) {
    val isConnected by viewModel.isConnected.collectAsState()
    val isStreamingScreen by viewModel.isStreamingScreen.collectAsState()
    val screenFrameB64 by viewModel.screenFrameBase64.collectAsState()

    val currentBitmap = remember(screenFrameB64) {
        screenFrameB64?.let {
            try {
                val bytes = Base64.decode(it, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (_: Exception) { null }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (isStreamingScreen) viewModel.stopScreenStream()
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0F))) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                }
                Text("Extended Workspace", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.weight(1f))
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

            Box(
                Modifier.weight(1f).fillMaxWidth().padding(16.dp).background(Color(0xFF111122), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (currentBitmap != null) {
                    Image(bitmap = currentBitmap, contentDescription = "Stream", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.DesktopWindows, null, tint = Color.Gray, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(if (!isConnected) "Not connected" else "Click Start to extend PC screen", color = Color.Gray)
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isStreamingScreen) {
                    Button(
                        onClick = { viewModel.startExtendScreenStream() },
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primary),
                        shape = RoundedCornerShape(16.dp),
                        enabled = isConnected
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = Color.Black)
                        Spacer(Modifier.width(8.dp))
                        Text("Start Extended Mode", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = { viewModel.stopScreenStream() },
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Stop, null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop Extending", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
