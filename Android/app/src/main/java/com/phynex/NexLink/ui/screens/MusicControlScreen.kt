package com.phynex.NexLink.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.phynex.NexLink.ui.theme.*
import com.phynex.NexLink.viewmodel.MainViewModel

@Composable
fun MusicControlScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val volume by viewModel.volume.collectAsState()
    val brightness by viewModel.brightness.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "disc")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing)),
        label = "disc_rot"
    )

    Box(
        Modifier.fillMaxSize()
            .background(background)
    ) {
        // Blur background (from album art if available)
        nowPlaying?.albumArtBase64?.let { b64 ->
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()?.let { bmp ->
                Image(
                    bmp.asImageBitmap(),
                    null,
                    Modifier.fillMaxSize().blur(80.dp),
                    contentScale = ContentScale.Crop,
                    alpha = 0.3f
                )
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(20.dp))

            // Top bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = onBackground)
                }
                Spacer(Modifier.weight(1f))
                Text("Now Playing", style = MaterialTheme.typography.titleMedium, color = onBackground, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, null, tint = onBackground) }
            }

            Spacer(Modifier.height(40.dp))

            // Album art poster
            Box(
                modifier = Modifier
                    .size(320.dp)
                    .shadow(24.dp, RoundedCornerShape(32.dp))
                    .background(Color(0xFF111111), RoundedCornerShape(32.dp)),
                contentAlignment = Alignment.Center
            ) {
                nowPlaying?.albumArtBase64?.let { b64 ->
                    runCatching {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()?.let { bmp ->
                        Image(
                            bmp.asImageBitmap(),
                            "Album Art",
                            Modifier.fillMaxSize().clip(RoundedCornerShape(32.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                } ?: Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(primary.copy(0.7f), background)), RoundedCornerShape(32.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(120.dp))
                }
            }

            Spacer(Modifier.height(48.dp))

            // Song info
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 32.dp)) {
                Text(
                    nowPlaying?.title ?: "Not Playing",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = onBackground,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    nowPlaying?.artist ?: "—",
                    style = MaterialTheme.typography.bodyLarge,
                    color = primary,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(40.dp))

            // Real-time Progress bar
            Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
                val position = nowPlaying?.position ?: 0.0
                val duration = nowPlaying?.duration ?: 0.0
                val progress = if (duration > 0) (position / duration).toFloat() else 0f

                var sliderValue by remember(progress) { mutableStateOf(progress) }

                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = {
                        val newPosition = (sliderValue * duration).toDouble()
                        viewModel.seekMedia(newPosition)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = primary,
                        activeTrackColor = primary,
                        inactiveTrackColor = primary.copy(0.2f)
                    )
                )
                
                fun formatTime(seconds: Double): String {
                    val m = (seconds / 60).toInt()
                    val s = (seconds % 60).toInt()
                    return String.format("%d:%02d", m, s)
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(position), style = MaterialTheme.typography.labelSmall, color = outline)
                    Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = outline)
                }
            }

            Spacer(Modifier.height(32.dp))

            // Playback controls
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.sendMediaControl("prev") }) {
                    Icon(Icons.Default.SkipPrevious, null, tint = onBackground, modifier = Modifier.size(36.dp))
                }
                
                Surface(
                    onClick = { viewModel.sendMediaControl("play_pause") },
                    shape = CircleShape,
                    color = primary,
                    modifier = Modifier.size(72.dp),
                    tonalElevation = 8.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (nowPlaying?.isPlaying == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                            null, tint = Color.White, modifier = Modifier.size(40.dp)
                        )
                    }
                }

                IconButton(onClick = { viewModel.sendMediaControl("next") }) {
                    Icon(Icons.Default.SkipNext, null, tint = onBackground, modifier = Modifier.size(36.dp))
                }
            }

            Spacer(Modifier.height(48.dp))

            // Volume/Brightness in glass card
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                RoundedCornerShape(28.dp),
                CardDefaults.cardColors(GlassSurface.copy(0.7f)),
                border = BorderStroke(1.dp, GlassBorder)
            ) {
                Column(Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.VolumeDown, null, tint = outline, modifier = Modifier.size(20.dp))
                        Slider(
                            value = volume.toFloat(), onValueChange = { viewModel.sendVolume(it.toInt()) },
                            valueRange = 0f..100f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            colors = SliderDefaults.colors(thumbColor = primary, activeTrackColor = primary)
                        )
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = outline, modifier = Modifier.size(20.dp))
                    }
                    
                    Spacer(Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BrightnessLow, null, tint = outline, modifier = Modifier.size(20.dp))
                        Slider(
                            value = brightness.toFloat(), onValueChange = { viewModel.sendBrightness(it.toInt()) },
                            valueRange = 0f..100f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            colors = SliderDefaults.colors(thumbColor = secondary, activeTrackColor = secondary)
                        )
                        Icon(Icons.Default.BrightnessHigh, null, tint = outline, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}
