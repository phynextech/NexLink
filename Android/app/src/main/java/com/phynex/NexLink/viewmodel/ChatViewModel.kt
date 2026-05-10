package com.phynex.NexLink.viewmodel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.database.FirebaseDatabase
import com.google.gson.JsonObject
import com.phynex.NexLink.model.ChatMessage
import com.phynex.NexLink.model.ChatMessageType
import com.phynex.NexLink.model.SavedTransfer
import com.phynex.NexLink.model.TransferState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

private const val TAG = "ChatViewModel"
private const val CHUNK_SIZE = 65536  // 64KB
private const val NOTIF_CHANNEL_ID = "nexlink_files"
private const val MAX_RELAY_BYTES = 500L * 1024 * 1024  // 500 MB

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    // ── State ─────────────────────────────────────────────────────────────
    private val _messages     = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _savedFiles   = MutableStateFlow<List<SavedTransfer>>(emptyList())
    val savedFiles: StateFlow<List<SavedTransfer>> = _savedFiles.asStateFlow()

    private val _isPeerOnline = MutableStateFlow(false)
    val isPeerOnline: StateFlow<Boolean> = _isPeerOnline.asStateFlow()

    private val _isTyping     = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    private val _searchQuery  = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // ── Encryption ────────────────────────────────────────────────────────
    var encryptionEnabled = false
    private var aesKey: ByteArray? = null

    // ── In-flight receive sessions ────────────────────────────────────────
    private val receiveBuffers = mutableMapOf<String, ReceiveSession>()

    // ── Socket accessor — set by MainActivity ─────────────────────────────
    var socketSend: ((String, JSONObject) -> Unit)? = null

    // ── Firebase ──────────────────────────────────────────────────────────
    private val db by lazy {
        try { FirebaseDatabase.getInstance().reference } catch (e: Exception) { null }
    }
    private var roomKey: String = ""

    // ── Local persistence ─────────────────────────────────────────────────
    private val prefs by lazy {
        app.getSharedPreferences("nexlink_chat_cache", android.content.Context.MODE_PRIVATE)
    }
    private val gson2 = com.google.gson.Gson()

    fun setRoomKey(key: String) {
        roomKey = key
        // Load cached history as soon as we know the room
        loadLocalCache()
    }

    private fun loadLocalCache() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = prefs.getString("history_$roomKey", null) ?: return@launch
                val type = object : com.google.gson.reflect.TypeToken<List<ChatMessage>>() {}.type
                val cached: List<ChatMessage> = gson2.fromJson(json, type) ?: return@launch
                if (cached.isNotEmpty() && _messages.value.isEmpty()) {
                    _messages.value = cached.sortedBy { it.timestamp }
                    Log.d(TAG, "Loaded ${cached.size} messages from local cache (room=$roomKey)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Local cache load failed: ${e.message}")
            }
        }
    }

    private fun saveLocalCache() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (roomKey.isEmpty()) return@launch
                val toSave = _messages.value.takeLast(300)
                val json   = gson2.toJson(toSave)
                prefs.edit().putString("history_$roomKey", json).apply()
            } catch (e: Exception) {
                Log.w(TAG, "Local cache save failed: ${e.message}")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // INCOMING EVENTS
    // ══════════════════════════════════════════════════════════════════════

    fun handleEvent(event: String, data: JsonObject) {
        when (event) {
            "chat_message"       -> handleChatMessage(data)
            "chat_file_offer"    -> handleFileOffer(data)
            "chat_file_chunk"    -> handleFileChunk(data)
            "chat_file_done"     -> handleFileDone(data)
            "chat_file_accept"   -> { /* outbound accepted — start send loop */ }
            "chat_file_pause"    -> updateMessageState(data.optStr("fileId"), TransferState.PAUSED)
            "chat_file_resume"   -> updateMessageState(data.optStr("fileId"), TransferState.TRANSFERRING)
            "chat_file_cancel"   -> updateMessageState(data.optStr("fileId"), TransferState.CANCELLED)
            "chat_typing"        -> _isTyping.value = data.get("isTyping")?.asBoolean ?: false
            "chat_delivered"     -> setDelivered(data.optStr("messageId"))
            "chat_read"          -> setRead(data.optStr("messageId"))
            "chat_reaction"      -> setReaction(data.optStr("messageId"), data.optStr("emoji"))
            "chat_history"       -> loadHistory(data)
            "chat_star"          -> setStar(data.optStr("messageId"), data.get("starred")?.asBoolean ?: false)
            "chat_clipboard"     -> addClipboardMessage(data.optStr("text"))
            "peer_online"        -> _isPeerOnline.value = true
            "peer_offline"       -> _isPeerOnline.value = false
        }
    }

    // ── Text message received from PC ─────────────────────────────────────
    private fun handleChatMessage(data: JsonObject) {
        val msgId   = data.optStr("messageId") ?: UUID.randomUUID().toString()
        val content = data.optStr("content") ?: ""
        val fileId  = data.optStr("fileId")
        val mime    = data.optStr("fileMime") ?: ""
        val fname   = data.optStr("fileName")
        val fsize   = data.get("fileSizeBytes")?.asLong ?: 0L
        val ts      = data.get("timestamp")?.asLong ?: System.currentTimeMillis()

        if (_messages.value.any { it.messageId == msgId }) return

        val type = if (fname != null) ChatMessage.mimeToType(mime) else ChatMessageType.TEXT
        val msg  = ChatMessage(
            messageId     = msgId,
            senderId      = "desktop",
            isSentByMe    = false,
            messageType   = type,
            content       = content,
            fileId        = fileId,
            fileName      = fname,
            mimeType      = mime,
            fileSizeBytes = fsize,
            timestamp     = ts,
            transferState = if (fileId != null) TransferState.OFFERED else TransferState.NONE,
        )
        addMessage(msg)
        emit("chat_delivered", mapOf("messageId" to msgId))
    }

    // ── File offered by PC ────────────────────────────────────────────────
    private fun handleFileOffer(data: JsonObject) {
        val fileId      = data.optStr("fileId") ?: return
        val name        = data.optStr("name") ?: "file"
        val size        = data.get("size")?.asLong ?: 0L
        val mime        = data.optStr("mimeType") ?: "application/octet-stream"
        val totalChunks = data.get("totalChunks")?.asInt ?: 1
        val msgId       = data.optStr("messageId") ?: UUID.randomUUID().toString()
        val encrypted   = data.get("encrypted")?.asBoolean ?: false

        val destFile = getDownloadFile(name)
        val session  = ReceiveSession(
            fileId      = fileId,
            destPath    = destFile.absolutePath,
            totalChunks = totalChunks,
            encrypted   = encrypted,
            mimeType    = mime,
            msgId       = msgId,
            fileSize    = size,
        )
        receiveBuffers[fileId] = session

        val msg = ChatMessage(
            messageId     = msgId,
            senderId      = "desktop",
            isSentByMe    = false,
            messageType   = ChatMessage.mimeToType(mime),
            fileName      = name,
            mimeType      = mime,
            fileSizeBytes = size,
            totalChunks   = totalChunks,
            fileId        = fileId,
            timestamp     = System.currentTimeMillis(),
            transferState = TransferState.OFFERED,
        )
        addMessage(msg)

        emit("chat_file_accept", mapOf("fileId" to fileId))
        updateMessageState(fileId, TransferState.TRANSFERRING)
    }

    // ── Chunk received ────────────────────────────────────────────────────
    private fun handleFileChunk(data: JsonObject) {
        val fileId    = data.optStr("fileId") ?: return
        val index     = data.get("index")?.asInt ?: return
        val total     = data.get("total")?.asInt ?: 1
        val data64    = data.optStr("data") ?: return
        val encrypted = data.get("encrypted")?.asBoolean ?: false
        val session   = receiveBuffers[fileId] ?: return

        if (session.receivedIndices.contains(index)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                var bytes = Base64.decode(data64, Base64.NO_WRAP)
                if (encrypted && aesKey != null) bytes = decryptChunk(bytes, index)

                FileOutputStream(session.destPath, true).use { fos ->
                    // Write at correct offset — reopen each time for correctness
                    val raf = java.io.RandomAccessFile(session.destPath, "rw")
                    raf.seek(index.toLong() * CHUNK_SIZE)
                    raf.write(bytes)
                    raf.close()
                }

                session.receivedIndices.add(index)
                emit("chat_file_ack", mapOf("fileId" to fileId, "chunkIndex" to index))

                val progress = session.receivedIndices.size.toFloat() / total
                val elapsed  = (System.currentTimeMillis() - session.startedAt) / 1000.0
                val bytesRcvd = session.receivedIndices.size.toLong() * CHUNK_SIZE
                val speed    = if (elapsed > 0) bytesRcvd / elapsed else 0.0
                val eta      = if (speed > 0) (session.fileSize - bytesRcvd) / speed else 0.0

                updateMessageProgress(
                    fileId, progress,
                    formatSpeed(speed),
                    formatEta(eta)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Chunk write error: ${e.message}")
            }
        }
    }

    // ── All chunks done ───────────────────────────────────────────────────
    private fun handleFileDone(data: JsonObject) {
        val fileId  = data.optStr("fileId") ?: return
        val session = receiveBuffers.remove(fileId) ?: return

        viewModelScope.launch {
            updateMessageProgress(fileId, 1f, "", "")
            updateMessageState(fileId, TransferState.COMPLETE)

            // Save metadata to Firebase
            if (roomKey.isNotEmpty()) {
                val savedTransfer = SavedTransfer(
                    fileId    = fileId,
                    fileName  = session.destPath.substringAfterLast("/"),
                    mimeType  = session.mimeType,
                    sizeBytes = session.fileSize,
                    localPath = session.destPath,
                )
                _savedFiles.value = _savedFiles.value + savedTransfer

                db?.child("chat")?.child(roomKey)?.child("transfers")?.child(fileId)?.updateChildren(
                    mapOf("status" to "complete", "completedAt" to System.currentTimeMillis(), "progress" to 100)
                )
            }

            emit("chat_delivered", mapOf("messageId" to session.msgId))
            showFileNotification(session.destPath.substringAfterLast("/"), session.destPath)
        }
    }

    // ── History ───────────────────────────────────────────────────────────
    private fun loadHistory(data: JsonObject) {
        val arr = data.getAsJsonArray("messages") ?: return
        val msgs = arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val m = el.asJsonObject
            ChatMessage(
                messageId     = m.optStr("messageId") ?: UUID.randomUUID().toString(),
                senderId      = m.optStr("senderId") ?: "",
                isSentByMe    = m.optStr("senderId") == "mobile",
                messageType   = ChatMessageType.valueOf(m.optStr("type")?.uppercase() ?: "TEXT").let { it },
                content       = m.optStr("content") ?: "",
                fileName      = m.optStr("fileName"),
                mimeType      = m.optStr("fileMime") ?: "",
                fileSizeBytes = m.get("fileSizeBytes")?.asLong ?: 0L,
                fileId        = m.optStr("fileId"),
                isDelivered   = m.get("isDelivered")?.asBoolean ?: false,
                isRead        = m.get("isRead")?.asBoolean ?: false,
                isStarred     = m.get("isStarred")?.asBoolean ?: false,
                reaction      = m.optStr("reaction"),
                transferState = TransferState.COMPLETE,
                timestamp     = m.get("timestamp")?.asLong ?: System.currentTimeMillis(),
            )
        }.sortedBy { it.timestamp }

        _messages.value = msgs
        saveLocalCache()
    }

    // ══════════════════════════════════════════════════════════════════════
    // SEND
    // ══════════════════════════════════════════════════════════════════════

    fun sendText(text: String) {
        if (text.isBlank()) return
        val msgId = UUID.randomUUID().toString()
        val msg   = ChatMessage(
            messageId  = msgId,
            senderId   = "mobile",
            isSentByMe = true,
            messageType= ChatMessageType.TEXT,
            content    = text,
            timestamp  = System.currentTimeMillis(),
        )
        addMessage(msg)
        emit("chat_message", mapOf(
            "messageId" to msgId,
            "content"   to text,
            "timestamp" to System.currentTimeMillis(),
        ))
        stopTyping()
    }

    fun sendFile(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name  = getFileName(context, uri)
                val size  = getFileSize(context, uri)
                val mime  = context.contentResolver.getType(uri) ?: ChatMessage.guessMime(name)
                val fileId= UUID.randomUUID().toString().replace("-","")
                val msgId = UUID.randomUUID().toString()
                val total = ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()

                val msg = ChatMessage(
                    messageId     = msgId,
                    senderId      = "mobile",
                    isSentByMe    = true,
                    messageType   = ChatMessage.mimeToType(mime),
                    fileName      = name,
                    mimeType      = mime,
                    fileSizeBytes = size,
                    totalChunks   = total,
                    fileId        = fileId,
                    timestamp     = System.currentTimeMillis(),
                    transferState = TransferState.OFFERED,
                )
                withContext(Dispatchers.Main) { addMessage(msg) }

                // Announce
                emit("chat_message", mapOf(
                    "messageId" to msgId, "content" to "📎 $name",
                    "fileName" to name, "fileMime" to mime,
                    "fileSizeBytes" to size, "fileId" to fileId,
                    "timestamp" to System.currentTimeMillis(),
                ))
                emit("chat_file_offer", mapOf(
                    "fileId" to fileId, "name" to name,
                    "size" to size, "mimeType" to mime,
                    "totalChunks" to total, "messageId" to msgId,
                    "encrypted" to (encryptionEnabled && aesKey != null),
                ))

                delay(300)
                withContext(Dispatchers.Main) { updateMessageState(fileId, TransferState.TRANSFERRING) }

                // Send chunks
                val startMs = System.currentTimeMillis()
                var bytesSent = 0L
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val buf = ByteArray(CHUNK_SIZE)
                    var i   = 0
                    while (true) {
                        val read = stream.read(buf)
                        if (read <= 0) break
                        val chunk = buf.copyOf(read)
                        val data64 = Base64.encodeToString(
                            if (encryptionEnabled && aesKey != null) encryptChunk(chunk, i) else chunk,
                            Base64.NO_WRAP
                        )
                        emit("chat_file_chunk", mapOf(
                            "fileId" to fileId, "index" to i,
                            "total" to total, "data" to data64,
                            "chunkSize" to read,
                            "encrypted" to (encryptionEnabled && aesKey != null),
                        ))
                        bytesSent += read
                        val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
                        val speed   = if (elapsed > 0) bytesSent / elapsed else 0.0
                        val eta     = if (speed > 0) (size - bytesSent) / speed else 0.0
                        val progress = bytesSent.toFloat() / size
                        withContext(Dispatchers.Main) {
                            updateMessageProgress(fileId, progress, formatSpeed(speed), formatEta(eta))
                        }
                        i++
                        if (i % 16 == 15) delay(1)
                    }
                }

                emit("chat_file_done", mapOf("fileId" to fileId))
                withContext(Dispatchers.Main) {
                    updateMessageState(fileId, TransferState.COMPLETE)
                    updateMessageProgress(fileId, 1f, "", "")
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendFile error: ${e.message}")
            }
        }
    }

    fun sendTyping(isTyping: Boolean) {
        emit("chat_typing", mapOf("isTyping" to isTyping))
    }

    fun stopTyping() { sendTyping(false) }

    fun sendClipboard(text: String) {
        addClipboardMessage(text, sentByMe = true)
        emit("chat_clipboard", mapOf("text" to text))
    }

    fun requestHistory() {
        emit("chat_history_req", mapOf("limit" to 100))
    }

    fun setReactionOnMessage(messageId: String, emoji: String) {
        updateMsg(messageId) { it.copy(reaction = emoji) }
        emit("chat_reaction", mapOf("messageId" to messageId, "emoji" to emoji))
    }

    fun starMessage(messageId: String, star: Boolean) {
        updateMsg(messageId) { it.copy(isStarred = star) }
        emit("chat_star", mapOf("messageId" to messageId, "starred" to star))
    }

    fun setSearchQuery(q: String) { _searchQuery.value = q }

    val filteredMessages: List<ChatMessage> get() {
        val q = _searchQuery.value.trim().lowercase()
        return if (q.isEmpty()) _messages.value
        else _messages.value.filter {
            it.content.lowercase().contains(q) || (it.fileName?.lowercase()?.contains(q) ?: false)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private fun addMessage(msg: ChatMessage) {
        _messages.value = _messages.value + msg
        saveLocalCache()
    }

    private fun addClipboardMessage(text: String?, sentByMe: Boolean = false) {
        if (text.isNullOrBlank()) return
        addMessage(ChatMessage(
            senderId    = if (sentByMe) "mobile" else "desktop",
            isSentByMe  = sentByMe,
            messageType = ChatMessageType.CLIPBOARD,
            content     = "📋 $text",
            timestamp   = System.currentTimeMillis(),
        ))
    }

    private fun updateMsg(msgId: String?, transform: (ChatMessage) -> ChatMessage) {
        if (msgId == null) return
        _messages.value = _messages.value.map { if (it.messageId == msgId) transform(it) else it }
    }

    private fun updateMessageState(fileId: String?, state: TransferState) {
        if (fileId == null) return
        _messages.value = _messages.value.map {
            if (it.fileId == fileId) it.copy(transferState = state) else it
        }
    }

    private fun updateMessageProgress(fileId: String, progress: Float, speed: String, eta: String) {
        _messages.value = _messages.value.map {
            if (it.fileId == fileId) it.copy(transferProgress = progress, speedLabel = speed, etaLabel = eta) else it
        }
    }

    private fun setDelivered(msgId: String?) { updateMsg(msgId) { it.copy(isDelivered = true) } }
    private fun setRead(msgId: String?)      { updateMsg(msgId) { it.copy(isRead = true) } }
    private fun setStar(msgId: String?, star: Boolean) { updateMsg(msgId) { it.copy(isStarred = star) } }
    private fun setReaction(msgId: String?, emoji: String?) { updateMsg(msgId) { it.copy(reaction = emoji) } }

    private fun emit(event: String, data: Map<String, Any?>) {
        val json = JSONObject(data.filterValues { it != null })
        socketSend?.invoke(event, json)
    }

    private fun getDownloadFile(name: String): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "NexLink"
        ).also { it.mkdirs() }
        var file = File(dir, name)
        var n = 1
        while (file.exists()) {
            val noExt = name.substringBeforeLast('.')
            val ext   = if ('.' in name) ".${name.substringAfterLast('.')}" else ""
            file = File(dir, "$noExt ($n)$ext")
            n++
        }
        return file
    }

    private fun getFileName(ctx: Context, uri: Uri): String {
        var name = "file_${System.currentTimeMillis()}"
        ctx.contentResolver.query(uri, null, null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                val idx = cur.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                if (idx >= 0) name = cur.getString(idx)
            }
        }
        return name
    }

    private fun getFileSize(ctx: Context, uri: Uri): Long {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) return cur.getLong(0)
        }
        return 0L
    }

    private fun showFileNotification(name: String, path: String) {
        val ctx = getApplication<Application>()
        val nm  = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL_ID, "NexLink Files", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notif = NotificationCompat.Builder(ctx, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("📁 File received")
            .setContentText("$name saved to Downloads/NexLink")
            .setAutoCancel(true)
            .build()
        nm.notify(path.hashCode(), notif)
    }

    // ── AES-256-GCM ───────────────────────────────────────────────────────
    fun generateAesKey(): ByteArray {
        aesKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return aesKey!!
    }

    private fun encryptChunk(data: ByteArray, index: Int): ByteArray {
        val key   = aesKey ?: return data
        val nonce = ByteArray(12).also { System.arraycopy(ByteArray(12).apply { this[0] = (index and 0xFF).toByte() }, 0, it, 0, 12) }
        val cipher= Cipher.getInstance("AES/GCM/NoPadding").also {
            it.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        }
        val cipher_text = cipher.doFinal(data)
        return nonce + cipher_text
    }

    private fun decryptChunk(data: ByteArray, index: Int): ByteArray {
        val key   = aesKey ?: return data
        return try {
            val nonce  = data.copyOf(12)
            val cipher_text = data.copyOfRange(12, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").also {
                it.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            }
            cipher.doFinal(cipher_text)
        } catch (e: Exception) { data }
    }

    // ── Formatting ─────────────────────────────────────────────────────────
    private fun formatSpeed(bps: Double): String = when {
        bps < 1024               -> "${bps.roundToInt()} B/s"
        bps < 1024 * 1024        -> "${"%.1f".format(bps / 1024)} KB/s"
        else                     -> "${"%.1f".format(bps / (1024 * 1024))} MB/s"
    }

    private fun formatEta(sec: Double): String = when {
        sec <= 0       -> ""
        sec < 60       -> "${sec.toInt()}s"
        sec < 3600     -> "${(sec / 60).toInt()}m ${(sec % 60).toInt()}s"
        else           -> "${(sec / 3600).toInt()}h ${((sec % 3600) / 60).toInt()}m"
    }

    // ── Session type ──────────────────────────────────────────────────────
    private data class ReceiveSession(
        val fileId:          String,
        val destPath:        String,
        val totalChunks:     Int,
        val encrypted:       Boolean,
        val mimeType:        String,
        val msgId:           String,
        val fileSize:        Long,
        val receivedIndices: MutableSet<Int> = mutableSetOf(),
        val startedAt:       Long = System.currentTimeMillis(),
    )
}

// Extension helper
private fun JsonObject.optStr(key: String): String? = try { get(key)?.asString } catch (_: Exception) { null }
