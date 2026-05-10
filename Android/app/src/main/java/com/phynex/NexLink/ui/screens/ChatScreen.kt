package com.phynex.NexLink.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.phynex.NexLink.model.ChatMessage
import com.phynex.NexLink.model.ChatMessageType
import com.phynex.NexLink.model.TransferState
import com.phynex.NexLink.ui.theme.*
import com.phynex.NexLink.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

// ── Telegram-inspired Design Tokens ─────────────────────────────────────────
private val TgBgDeep       = Color(0xFF0E1621)   // Telegram dark background
private val TgBgHeader     = Color(0xFF17212B)   // Header bar
private val TgBubbleSent   = Color(0xFF2B5278)   // Telegram sent blue
private val TgBubbleRecv   = Color(0xFF182533)   // Received dark
private val TgAccent       = Color(0xFF5AACF5)   // Telegram light blue
private val TgAccentGlow   = Color(0xFF2B82CC)
private val TgTextPrimary  = Color(0xFFD8EEFF)
private val TgTextSecond   = Color(0xFF7DAEC8)
private val TgTextMuted    = Color(0xFF4D7EA8)
private val TgBorder       = Color(0xFF1E3A52)
private val TgGreen        = Color(0xFF2CA567)   // Online dot

@Composable
fun ChatScreen(
    chatVm: ChatViewModel = viewModel(),
    onBack: () -> Unit = {},
) {
    val context      = LocalContext.current
    val messages     by chatVm.messages.collectAsState()
    val isPeerOnline by chatVm.isPeerOnline.collectAsState()
    val isTyping     by chatVm.isTyping.collectAsState()
    val searchQuery  by chatVm.searchQuery.collectAsState()

    val listState   = rememberLazyListState()
    val scope       = rememberCoroutineScope()
    var inputText   by remember { mutableStateOf("") }
    var showSearch  by remember { mutableStateOf(false) }
    var showStarred by remember { mutableStateOf(false) }

    val displayedMessages = remember(messages, searchQuery, showStarred) {
        when {
            showStarred     -> messages.filter { it.isStarred }
            searchQuery.isNotEmpty() -> messages.filter {
                it.content.contains(searchQuery, ignoreCase = true) ||
                it.fileName?.contains(searchQuery, ignoreCase = true) == true
            }
            else -> messages
        }
    }

    // File picker
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        uris.forEach { uri -> chatVm.sendFile(context, uri) }
    }

    // Auto-scroll to bottom
    LaunchedEffect(displayedMessages.size) {
        if (displayedMessages.isNotEmpty())
            scope.launch { listState.animateScrollToItem(displayedMessages.size - 1) }
    }

    LaunchedEffect(Unit) { chatVm.requestHistory() }

    Scaffold(
        containerColor = TgBgDeep,
        topBar = {
            TgTopBar(
                isPeerOnline    = isPeerOnline,
                showSearch      = showSearch,
                searchQuery     = searchQuery,
                onSearchToggle  = {
                    showSearch = !showSearch
                    if (!showSearch) chatVm.setSearchQuery("")
                },
                onSearchChange  = { chatVm.setSearchQuery(it) },
                onStarredToggle = { showStarred = !showStarred },
                onBack          = onBack,
            )
        },
        bottomBar = {
            TgInputBar(
                text         = inputText,
                onTextChange = { inputText = it; chatVm.sendTyping(it.isNotEmpty()) },
                onSend       = {
                    if (inputText.isNotBlank()) {
                        chatVm.sendText(inputText)
                        inputText = ""
                    }
                },
                onAttach     = { filePicker.launch("*/*") },
                onClipboard  = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                             as android.content.ClipboardManager
                    val clip = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    if (clip.isNotEmpty()) chatVm.sendClipboard(clip)
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(TgBgDeep)
        ) {
            // Subtle Telegram-style dot pattern
            Canvas(Modifier.fillMaxSize()) {
                val stepX = 30.dp.toPx(); val stepY = 30.dp.toPx()
                var y = 0f
                while (y < size.height) {
                    var x = 0f
                    while (x < size.width) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.02f),
                            radius = 1.5.dp.toPx(),
                            center = androidx.compose.ui.geometry.Offset(x, y)
                        )
                        x += stepX
                    }
                    y += stepY
                }
            }

            // Message list
            LazyColumn(
                state              = listState,
                modifier           = Modifier.fillMaxSize(),
                contentPadding     = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement= Arrangement.spacedBy(2.dp),
            ) {
                items(
                    items = displayedMessages,
                    key   = { it.messageId }
                ) { msg ->
                    AnimatedVisibility(
                        visible = true,
                        enter   = slideInVertically(initialOffsetY = { it / 3 }) + fadeIn(tween(200)),
                    ) {
                        TgMessageItem(
                            msg     = msg,
                            onReact = { emoji -> chatVm.setReactionOnMessage(msg.messageId, emoji) },
                            onStar  = { chatVm.starMessage(msg.messageId, !msg.isStarred) },
                            onPause = { /* TODO */ },
                        )
                    }
                }
            }

            // Typing indicator
            AnimatedVisibility(
                visible  = isTyping,
                enter    = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit     = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 4.dp),
            ) {
                TgTypingIndicator()
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// TOP BAR
// ══════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TgTopBar(
    isPeerOnline:    Boolean,
    showSearch:      Boolean,
    searchQuery:     String,
    onSearchToggle:  () -> Unit,
    onSearchChange:  (String) -> Unit,
    onStarredToggle: () -> Unit,
    onBack:          () -> Unit,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = TgBgHeader),
        title  = {
            if (showSearch) {
                TextField(
                    value         = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder   = { Text("Search messages…", color = TgTextMuted, fontSize = 14.sp) },
                    singleLine    = true,
                    colors        = TextFieldDefaults.colors(
                        focusedContainerColor   = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor   = TgAccent,
                        unfocusedIndicatorColor = TgBorder,
                        focusedTextColor        = TgTextPrimary,
                        unfocusedTextColor      = TgTextPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Avatar
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(TgAccentGlow, TgAccent))),
                        contentAlignment = Alignment.Center
                    ) { Text("💻", fontSize = 20.sp) }

                    Spacer(Modifier.width(12.dp))

                    Column {
                        Text(
                            "Windows PC",
                            fontSize    = 16.sp,
                            fontWeight  = FontWeight.SemiBold,
                            color       = TgTextPrimary,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isPeerOnline) TgGreen else TgTextMuted)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (isPeerOnline) "online" else "offline",
                                fontSize = 12.sp,
                                color    = if (isPeerOnline) TgGreen else TgTextMuted,
                            )
                        }
                    }
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, null, tint = TgTextSecond)
            }
        },
        actions = {
            IconButton(onClick = onStarredToggle) {
                Text("⭐", fontSize = 20.sp)
            }
            IconButton(onClick = onSearchToggle) {
                Icon(
                    if (showSearch) Icons.Default.Close else Icons.Default.Search,
                    null,
                    tint = TgTextSecond,
                )
            }
        },
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// MESSAGE ITEM
// ══════════════════════════════════════════════════════════════════════════════
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TgMessageItem(
    msg:     ChatMessage,
    onReact: (String) -> Unit,
    onStar:  () -> Unit,
    onPause: () -> Unit,
) {
    var showReactions by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (msg.isSentByMe) 60.dp else 4.dp,
                end   = if (msg.isSentByMe) 4.dp  else 60.dp,
            ),
        horizontalAlignment = if (msg.isSentByMe) Alignment.End else Alignment.Start,
    ) {

        // Reaction picker
        AnimatedVisibility(showReactions) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(TgBgHeader)
                    .border(1.dp, TgBorder, RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("❤️", "😂", "👍", "🔥", "😮", "😢").forEach { emoji ->
                    Text(
                        emoji,
                        fontSize = 22.sp,
                        modifier = Modifier.clickable {
                            onReact(emoji)
                            showReactions = false
                        }
                    )
                }
            }
        }

        // Bubble
        Box(
            modifier = Modifier.combinedClickable(
                onClick     = {},
                onLongClick = { showReactions = !showReactions },
            )
        ) {
            if (msg.isFileMessage) TgFileCard(msg = msg, onPause = onPause)
            else                   TgTextBubble(msg = msg)
        }

        // Reaction & star badges
        Row(
            horizontalArrangement = if (msg.isSentByMe) Arrangement.End else Arrangement.Start,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            if (!msg.reaction.isNullOrEmpty())
                Text(msg.reaction, fontSize = 16.sp)
            if (msg.isStarred)
                Text("⭐", fontSize = 12.sp)
        }
    }
}

// ── Text bubble ─────────────────────────────────────────────────────────────
@Composable
private fun TgTextBubble(msg: ChatMessage) {
    val sentShape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    val recvShape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    val shape = if (msg.isSentByMe) sentShape else recvShape

    Surface(
        shape = shape,
        color = if (msg.isSentByMe) TgBubbleSent else TgBubbleRecv,
        border = if (!msg.isSentByMe) BorderStroke(0.5.dp, TgBorder) else null,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Reply preview
            if (!msg.replyPreview.isNullOrEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.07f))
                        .padding(start = 8.dp, top = 4.dp, bottom = 4.dp, end = 6.dp)
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(30.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(TgAccent)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        msg.replyPreview,
                        fontSize  = 11.sp,
                        color     = TgAccent,
                        maxLines  = 1,
                        overflow  = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            Text(msg.content, fontSize = 14.sp, color = TgTextPrimary, lineHeight = 20.sp)

            // Footer
            Row(
                Modifier.align(Alignment.End).padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(msg.timeLabel, fontSize = 10.sp, color = TgTextMuted)
                if (msg.isSentByMe) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        msg.deliveryIcon,
                        fontSize = 10.sp,
                        color    = if (msg.isRead) TgAccent else TgTextMuted,
                    )
                }
            }
        }
    }
}

// ── File card ────────────────────────────────────────────────────────────────
@Composable
private fun TgFileCard(msg: ChatMessage, onPause: () -> Unit) {
    val context = LocalContext.current
    val shape   = RoundedCornerShape(12.dp)

    Surface(
        shape  = shape,
        color  = TgBgHeader,
        border = BorderStroke(1.dp, TgBorder),
    ) {
        Column(
            Modifier
                .widthIn(min = 220.dp, max = 320.dp)
                .padding(12.dp)
        ) {
            // Image inline preview
            if (msg.isImageMessage && !msg.localFilePath.isNullOrEmpty()) {
                AsyncImage(
                    model              = msg.localFilePath,
                    contentDescription = msg.fileName,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.height(8.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Icon circle
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(TgBgDeep),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(msg.fileIcon, fontSize = 22.sp)
                }

                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        msg.fileName ?: "file",
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color      = TgTextPrimary,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                    )
                    Row {
                        Text(msg.fileSizeLabel, fontSize = 11.sp, color = TgTextSecond)
                        if (msg.speedLabel.isNotEmpty())
                            Text(" · ${msg.speedLabel}", fontSize = 11.sp, color = TgAccent)
                    }
                }

                // Action button
                when (msg.transferState) {
                    TransferState.TRANSFERRING -> {
                        IconButton(onClick = onPause, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Pause, null, tint = TgAccent)
                        }
                    }
                    TransferState.COMPLETE -> {
                        IconButton(
                            onClick = {
                                msg.localFilePath?.takeIf { it.isNotEmpty() }?.let { path ->
                                    val file = java.io.File(path)
                                    if (file.exists()) {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                            setDataAndType(
                                                androidx.core.content.FileProvider.getUriForFile(
                                                    context, "${context.packageName}.provider", file),
                                                msg.mimeType ?: "*/*"
                                            )
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(intent)
                                    }
                                }
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Default.FolderOpen, null, tint = TgAccent)
                        }
                    }
                    else -> {}
                }
            }

            // Progress bar
            if (msg.transferState == TransferState.TRANSFERRING && msg.transferProgress > 0) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress   = { msg.transferProgress },
                    modifier   = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(1.5.dp)),
                    color      = TgAccent,
                    trackColor = TgBgDeep,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${msg.progressPercent}%", fontSize = 10.sp, color = TgTextMuted)
                    if (msg.etaLabel.isNotEmpty())
                        Text(msg.etaLabel, fontSize = 10.sp, color = TgTextMuted)
                }
            }

            // Timestamp
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(msg.timeLabel, fontSize = 10.sp, color = TgTextMuted)
                if (msg.isSentByMe) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        msg.deliveryIcon,
                        fontSize = 10.sp,
                        color    = if (msg.isRead) TgAccent else TgTextMuted,
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// INPUT BAR
// ══════════════════════════════════════════════════════════════════════════════
@Composable
private fun TgInputBar(
    text:         String,
    onTextChange: (String) -> Unit,
    onSend:       () -> Unit,
    onAttach:     () -> Unit,
    onClipboard:  () -> Unit,
) {
    Surface(
        color         = TgBgHeader,
        tonalElevation= 0.dp,
        shadowElevation= 8.dp,
    ) {
        Row(
            modifier           = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment  = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onAttach) {
                Icon(Icons.Default.AttachFile, null, tint = TgTextSecond, modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = onClipboard) {
                Icon(Icons.Default.ContentPaste, null, tint = TgTextSecond, modifier = Modifier.size(22.dp))
            }

            // Input field
            Surface(
                modifier      = Modifier.weight(1f).padding(horizontal = 4.dp),
                shape         = RoundedCornerShape(24.dp),
                color         = TgBgDeep,
                border        = BorderStroke(1.dp, TgBorder),
            ) {
                BasicTextField(
                    value         = text,
                    onValueChange = onTextChange,
                    textStyle     = LocalTextStyle.current.copy(
                        color    = TgTextPrimary,
                        fontSize = 14.sp,
                    ),
                    decorationBox = { inner ->
                        Box(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (text.isEmpty())
                                Text("Write a message…", color = TgTextMuted, fontSize = 14.sp)
                            inner()
                        }
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSend = { onSend() },
                    ),
                    maxLines = 6,
                )
            }

            Spacer(Modifier.width(6.dp))

            // Send button
            Box(
                modifier         = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(TgAccentGlow)
                    .clickable(onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Send, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// TYPING INDICATOR
// ══════════════════════════════════════════════════════════════════════════════
@Composable
private fun TgTypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val offsets = (0..2).map { i ->
        infiniteTransition.animateFloat(
            initialValue  = 0f,
            targetValue   = -5f,
            animationSpec = infiniteRepeatable(
                tween(300, delayMillis = i * 100, easing = EaseInOutSine),
                RepeatMode.Reverse
            ),
            label         = "dot$i",
        )
    }

    Surface(
        color = TgBgHeader,
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
        border = BorderStroke(1.dp, TgBorder),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            offsets.forEach { offset ->
                Box(
                    Modifier
                        .offset(y = offset.value.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(TgAccent)
                )
            }
        }
    }
}

// ── Canvas import ─────────────────────────────────────────────────────────────
@Composable
private fun Canvas(modifier: Modifier, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    androidx.compose.foundation.Canvas(modifier = modifier, onDraw = block)
}

// ── Aliases for backward-compat (called from MainActivity) ───────────────────
@Composable
fun ChatMessageItem(
    msg:     ChatMessage,
    onReact: (String) -> Unit,
    onStar:  () -> Unit,
    onPause: () -> Unit,
) = TgMessageItem(msg = msg, onReact = onReact, onStar = onStar, onPause = onPause)
