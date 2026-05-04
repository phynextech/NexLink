package com.phynex.NexLink.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.phynex.NexLink.ui.theme.*
import com.phynex.NexLink.viewmodel.MainViewModel

@Composable
fun MusicControlScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val volume by viewModel.volume.collectAsState()
    val deviceInfo by viewModel.connectedDevice.collectAsState()

    // Decode album art bitmap
    val albumBitmap = remember(nowPlaying?.albumArtBase64) {
        nowPlaying?.albumArtBase64?.let { b64 ->
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }

    // Local like state
    var isLiked by remember(nowPlaying?.title) { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(background)) {

        // Blurred album art as full-screen background
        albumBitmap?.let { bmp ->
            Image(
                bmp.asImageBitmap(), null,
                Modifier.fillMaxSize().blur(60.dp),
                contentScale = ContentScale.Crop,
                alpha = 0.25f
            )
        }

        // Dark gradient overlay
        Box(
            Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(0.3f),
                            background.copy(0.75f),
                            background,
                            background
                        )
                    )
                )
        )

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(52.dp))

            // ── Top Bar ──────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.KeyboardArrowDown, "Back", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "NOW PLAYING",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(0.6f),
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        nowPlaying?.appSource?.ifEmpty { "Music" } ?: "Music",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(0.85f),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                IconButton(onClick = {}) {
                    Icon(Icons.Default.MoreVert, null, tint = Color.White)
                }
            }

            Spacer(Modifier.height(32.dp))

            // ── Album Art ────────────────────────────────────────────────
            Box(
                Modifier
                    .size(300.dp)
                    .shadow(
                        elevation = 32.dp,
                        shape = RoundedCornerShape(24.dp),
                        ambientColor = primary.copy(0.5f),
                        spotColor = primary.copy(0.5f)
                    )
                    .background(Color(0xFF1A1A2E), RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (albumBitmap != null) {
                    Image(
                        albumBitmap.asImageBitmap(), "Album Art",
                        Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize()
                            .background(
                                Brush.radialGradient(listOf(primary.copy(0.4f), Color(0xFF0D0D1A))),
                                RoundedCornerShape(24.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(100.dp))
                    }
                }
            }

            Spacer(Modifier.height(40.dp))

            // ── Track Info + Like Button ─────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        nowPlaying?.title ?: "Not Playing",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        nowPlaying?.artist ?: "—",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(16.dp))
                IconButton(onClick = { isLiked = !isLiked }) {
                    Icon(
                        if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        "Like",
                        tint = if (isLiked) Color(0xFF1DB954) else Color.White.copy(0.5f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── Seek Bar ─────────────────────────────────────────────────
            Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
                val basePosition = nowPlaying?.position ?: 0.0
                val duration = nowPlaying?.duration ?: 0.0
                val isPlaying = nowPlaying?.isPlaying == true

                var currentPosition by remember(basePosition, isPlaying) { mutableStateOf(basePosition) }
                val lastUpdateMillis = remember(basePosition, isPlaying) { System.currentTimeMillis() }
                
                var isDragging by remember { mutableStateOf(false) }

                LaunchedEffect(basePosition, isPlaying, isDragging) {
                    if (isPlaying && !isDragging) {
                        while (true) {
                            kotlinx.coroutines.delay(16)
                            val elapsed = (System.currentTimeMillis() - lastUpdateMillis) / 1000.0
                            currentPosition = (basePosition + elapsed).coerceAtMost(duration)
                        }
                    }
                }

                val progress = if (duration > 0) (currentPosition / duration).toFloat().coerceIn(0f, 1f) else 0f
                var sliderValue by remember(progress) { mutableStateOf(progress) }

                Slider(
                    value = if (isDragging) sliderValue else progress,
                    onValueChange = { 
                        isDragging = true
                        sliderValue = it 
                    },
                    onValueChangeFinished = {
                        isDragging = false
                        val newPosition = (sliderValue * duration)
                        viewModel.seekMedia(newPosition)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(0.2f)
                    )
                )

                fun formatTime(seconds: Double): String {
                    if (seconds.isNaN() || seconds.isInfinite()) return "0:00"
                    val m = (seconds / 60).toInt()
                    val s = (seconds % 60).toInt()
                    return String.format("%d:%02d", m, s)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(if (isDragging) sliderValue * duration else currentPosition), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(0.5f))
                    Text(formatTime(duration),  style = MaterialTheme.typography.labelSmall, color = Color.White.copy(0.5f))
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Playback Controls ─────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shuffle
                val shuffleOn = nowPlaying?.shuffleActive == true
                IconButton(onClick = { viewModel.sendMediaControl("shuffle") }) {
                    Icon(
                        Icons.Default.Shuffle, "Shuffle",
                        tint = if (shuffleOn) Color(0xFF1DB954) else Color.White.copy(0.6f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Prev
                IconButton(onClick = { viewModel.sendMediaControl("prev") }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Prev", tint = Color.White, modifier = Modifier.size(36.dp))
                }

                // Play/Pause — main button
                Surface(
                    onClick = { viewModel.sendMediaControl("play_pause") },
                    shape = CircleShape,
                    color = Color.White,
                    modifier = Modifier.size(64.dp),
                    shadowElevation = 12.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (nowPlaying?.isPlaying == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                            null,
                            tint = Color.Black,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // Next
                IconButton(onClick = { viewModel.sendMediaControl("next") }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Default.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(36.dp))
                }

                // Repeat
                val repeatMode = nowPlaying?.repeatMode ?: 0
                IconButton(onClick = { viewModel.sendMediaControl("repeat") }) {
                    Icon(
                        if (repeatMode == 2) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Repeat",
                        tint = if (repeatMode > 0) Color(0xFF1DB954) else Color.White.copy(0.6f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Volume removed to make layout full screen
            Spacer(Modifier.height(10.dp))
        }
    }
}
