package com.phynex.NexLink.model

import java.util.UUID

// ── Transfer state ──────────────────────────────────────────────────────────
enum class TransferState { NONE, OFFERED, ACCEPTED, TRANSFERRING, PAUSED, COMPLETE, FAILED, CANCELLED }

// ── Message type ────────────────────────────────────────────────────────────
enum class ChatMessageType { TEXT, IMAGE, VIDEO, AUDIO, VOICE_NOTE, DOCUMENT, APK, ARCHIVE, FILE, CLIPBOARD, SCREENSHOT }

// ── Core chat message ───────────────────────────────────────────────────────
data class ChatMessage(
    val messageId:      String           = UUID.randomUUID().toString(),
    val senderId:       String           = "",        // "desktop" | "mobile"
    val isSentByMe:     Boolean          = false,
    val messageType:    ChatMessageType  = ChatMessageType.TEXT,
    val content:        String           = "",
    val fileId:         String?          = null,
    val fileName:       String?          = null,
    val mimeType:       String?          = null,
    val fileSizeBytes:  Long             = 0L,
    val totalChunks:    Int              = 0,
    val localFilePath:  String?          = null,
    val replyToId:      String?          = null,
    val replyPreview:   String?          = null,
    val reaction:       String?          = null,
    val isStarred:      Boolean          = false,
    val isDelivered:    Boolean          = false,
    val isRead:         Boolean          = false,
    val timestamp:      Long             = System.currentTimeMillis(),
    val transferProgress: Float          = 0f,        // 0.0 – 1.0
    val transferState:  TransferState    = TransferState.NONE,
    val speedLabel:     String           = "",
    val etaLabel:       String           = "",
) {
    val timeLabel: String get() {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }
    val fileSizeLabel: String get() = formatBytes(fileSizeBytes)
    val progressPercent: Int get() = (transferProgress * 100).toInt()
    val deliveryIcon: String get() = when {
        isRead      -> "✓✓"
        isDelivered -> "✓"
        else        -> "○"
    }
    val fileIcon: String get() = when (messageType) {
        ChatMessageType.APK        -> "📦"
        ChatMessageType.ARCHIVE    -> "🗜"
        ChatMessageType.DOCUMENT   -> "📄"
        ChatMessageType.AUDIO      -> "🎵"
        ChatMessageType.VOICE_NOTE -> "🎙"
        ChatMessageType.VIDEO      -> "🎬"
        ChatMessageType.IMAGE      -> "🖼"
        ChatMessageType.CLIPBOARD  -> "📋"
        ChatMessageType.SCREENSHOT -> "🖥"
        else                       -> "📁"
    }
    val isFileMessage: Boolean get() = messageType != ChatMessageType.TEXT
    val isImageMessage: Boolean get() = messageType == ChatMessageType.IMAGE
    val isAudioMessage: Boolean get() = messageType == ChatMessageType.AUDIO || messageType == ChatMessageType.VOICE_NOTE

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes <= 0L                  -> ""
            bytes < 1024                 -> "$bytes B"
            bytes < 1024 * 1024          -> "${"%.1f".format(bytes / 1024.0)} KB"
            bytes < 1024L * 1024 * 1024  -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
            else                         -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
        }

        fun mimeToType(mime: String): ChatMessageType = when {
            mime.startsWith("image/")    -> ChatMessageType.IMAGE
            mime.startsWith("video/")    -> ChatMessageType.VIDEO
            mime.startsWith("audio/")    -> ChatMessageType.AUDIO
            mime == "application/vnd.android.package-archive" -> ChatMessageType.APK
            mime.contains("zip") || mime.contains("rar") || mime.contains("7z") -> ChatMessageType.ARCHIVE
            mime.contains("pdf") || mime.contains("word") || mime.contains("text/") -> ChatMessageType.DOCUMENT
            else                         -> ChatMessageType.FILE
        }

        fun guessMime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
            "jpg","jpeg"  -> "image/jpeg"
            "png"         -> "image/png"
            "gif"         -> "image/gif"
            "webp"        -> "image/webp"
            "mp4"         -> "video/mp4"
            "mkv"         -> "video/x-matroska"
            "mp3"         -> "audio/mpeg"
            "m4a"         -> "audio/mp4"
            "wav"         -> "audio/wav"
            "ogg"         -> "audio/ogg"
            "pdf"         -> "application/pdf"
            "zip"         -> "application/zip"
            "rar"         -> "application/x-rar-compressed"
            "7z"          -> "application/x-7z-compressed"
            "apk"         -> "application/vnd.android.package-archive"
            "txt","csv"   -> "text/plain"
            else          -> "application/octet-stream"
        }
    }
}

// ── Saved transfer entry ────────────────────────────────────────────────────
data class SavedTransfer(
    val fileId:     String = "",
    val fileName:   String = "",
    val mimeType:   String = "",
    val sizeBytes:  Long   = 0L,
    val localPath:  String = "",
    val receivedAt: Long   = System.currentTimeMillis(),
    val isStarred:  Boolean= false,
    val direction:  String = "received",
) {
    val sizeLabel: String get() = ChatMessage.formatBytes(sizeBytes)
}
