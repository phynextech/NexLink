package com.phynex.NexLink.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phynex.NexLink.ui.theme.*
import com.phynex.NexLink.viewmodel.MainViewModel

@Composable
fun TrackpadScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    var sensitivity by remember { mutableFloatStateOf(1.0f) }

    Box(Modifier.fillMaxSize().background(background)) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            // Top Bar
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = primary)
                }
                Spacer(Modifier.weight(1f))
                Text("Sensitivity", color = outline, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = sensitivity,
                    onValueChange = { sensitivity = it },
                    valueRange = 0.1f..3.0f,
                    modifier = Modifier.width(200.dp).padding(start = 16.dp)
                )
            }

            // Trackpad + Scroll Row
            Row(Modifier.weight(1f).fillMaxWidth()) {
                // Trackpad
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .background(Color(0xFF1A1A2E), RoundedCornerShape(24.dp))
                        .border(2.dp, primary.copy(0.3f), RoundedCornerShape(24.dp))
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                viewModel.sendMouseMove(dragAmount.x * sensitivity, dragAmount.y * sensitivity)
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { viewModel.sendMouseTap() },
                                onLongPress = { viewModel.sendMouseRightTap() }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("TRACKPAD", color = outline.copy(0.3f), style = MaterialTheme.typography.titleLarge)
                }

                Spacer(Modifier.width(16.dp))

                // Scroll
                Box(
                    Modifier.width(60.dp).fillMaxHeight()
                        .background(Color(0xFF1A1A2E), RoundedCornerShape(24.dp))
                        .border(2.dp, outlineVariant.copy(0.3f), RoundedCornerShape(24.dp))
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                viewModel.sendMouseScroll(dragAmount.y * sensitivity)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("S\nC\nR\nO\nL\nL", color = outline.copy(0.5f))
                }
            }

            Spacer(Modifier.height(16.dp))

            // Bottom Buttons (L, M, R)
            Row(Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { viewModel.sendMouseTap() },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primary.copy(0.2f))
                ) { Text("Left Click", color = primary) }

                Button(
                    onClick = { viewModel.sendMouseMiddleTap() },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = outlineVariant.copy(0.2f))
                ) { Text("Middle Click", color = outline) }

                Button(
                    onClick = { viewModel.sendMouseRightTap() },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = outlineVariant.copy(0.2f))
                ) { Text("Right Click", color = outline) }
            }
        }
    }
}
