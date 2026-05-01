package com.phynex.NexLink.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.phynex.NexLink.model.ClipboardItem
import com.phynex.NexLink.ui.theme.*
import com.phynex.NexLink.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ClipboardScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val clipboardItems by viewModel.clipboardItems.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new message
    LaunchedEffect(clipboardItems.size) {
        if (clipboardItems.isNotEmpty())
            listState.animateScrollToItem(clipboardItems.size - 1)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(background)
    ) {
        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(surface)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = onBackground)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(40.dp)
                    .background(primary.copy(0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Computer, null, tint = primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("PC Clipboard", style = MaterialTheme.typography.titleMedium, color = onBackground, fontWeight = FontWeight.Bold)
                Text("Synced in real-time", style = MaterialTheme.typography.bodySmall, color = primary)
            }
            IconButton(onClick = { viewModel.pullClipboard() }) {
                Icon(Icons.Default.Refresh, "Pull from PC", tint = primary)
            }
        }

        // Messages area
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (clipboardItems.isEmpty()) {
                item {
                    Column(
                        Modifier.fillParentMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.ContentPaste, null, tint = outline, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("No clipboard items yet", style = MaterialTheme.typography.bodyLarge, color = outline)
                        Text("Copy something on PC or phone", style = MaterialTheme.typography.bodySmall, color = outline, textAlign = TextAlign.Center)
                    }
                }
            }

            items(clipboardItems) { item ->
                ClipboardBubble(item = item, onCopy = {
                    viewModel.pushClipboard(item.content)
                })
            }
        }

        // Input bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type to send to PC clipboard...", color = outline) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = onBackground,
                    unfocusedTextColor = onBackground,
                    focusedBorderColor = primary,
                    unfocusedBorderColor = outlineVariant,
                    focusedContainerColor = GlassSurface,
                    unfocusedContainerColor = GlassSurface
                ),
                shape = RoundedCornerShape(24.dp),
                maxLines = 3,
                singleLine = false
            )
            Spacer(Modifier.width(8.dp))
            FloatingActionButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        viewModel.pushClipboard(inputText)
                        inputText = ""
                    }
                },
                containerColor = primary,
                contentColor = Color.White,
                modifier = Modifier.size(48.dp),
                shape = CircleShape
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ClipboardBubble(item: ClipboardItem, onCopy: () -> Unit) {
    val isFromPC = item.source == "pc"
    val timeStr = remember(item.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.timestamp))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isFromPC) Arrangement.Start else Arrangement.End
    ) {
        if (isFromPC) {
            Box(
                Modifier
                    .size(32.dp)
                    .background(primary.copy(0.15f), CircleShape)
                    .align(Alignment.Bottom),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Computer, null, tint = primary, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 280.dp),
            horizontalAlignment = if (isFromPC) Alignment.Start else Alignment.End
        ) {
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isFromPC) 4.dp else 16.dp,
                            bottomEnd = if (isFromPC) 16.dp else 4.dp
                        )
                    )
                    .background(if (isFromPC) GlassSurface else primary.copy(0.85f))
                    .border(
                        1.dp,
                        if (isFromPC) GlassBorder else Color.Transparent,
                        RoundedCornerShape(
                            topStart = 16.dp, topEnd = 16.dp,
                            bottomStart = if (isFromPC) 4.dp else 16.dp,
                            bottomEnd = if (isFromPC) 16.dp else 4.dp
                        )
                    )
                    .clickable { onCopy() }
                    .padding(12.dp)
            ) {
                if (item.isImage && item.content.isNotEmpty()) {
                    // Render image clipboard
                    runCatching {
                        val bytes = Base64.decode(item.content, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()?.let { bmp ->
                        Image(
                            bmp.asImageBitmap(), "Clipboard Image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    } ?: Text("[Image]", color = if (isFromPC) onBackground else Color.White)
                } else {
                    Text(
                        text = item.content,
                        color = if (isFromPC) onBackground else Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    if (isFromPC) "PC → Phone" else "Phone → PC",
                    style = MaterialTheme.typography.labelSmall,
                    color = outline,
                    modifier = Modifier.weight(1f)
                )
                Text(timeStr, style = MaterialTheme.typography.labelSmall, color = outline)
            }
        }

        if (!isFromPC) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(32.dp)
                    .background(primary.copy(0.15f), CircleShape)
                    .align(Alignment.Bottom),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PhoneAndroid, null, tint = primary, modifier = Modifier.size(16.dp))
            }
        }
    }
}
