package com.phynex.NexLink.viewmodel

import android.app.Application
import android.os.Environment
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.gson.JsonElement
import com.google.gson.JsonObject

// ── Safe JSON helpers (guard against JsonArray instead of JsonPrimitive) ───
private fun JsonObject.safeStr(key: String, default: String = ""): String =
    try { get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: default } catch (_: Exception) { default }
private fun JsonObject.safeStr(key: String): String? =
    try { get(key)?.takeIf { it.isJsonPrimitive }?.asString } catch (_: Exception) { null }
private fun JsonObject.safeInt(key: String, default: Int = 0): Int =
    try { get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: default } catch (_: Exception) { default }
private fun JsonObject.safeBool(key: String, default: Boolean = false): Boolean =
    try { get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: default } catch (_: Exception) { default }
private fun JsonObject.safeDouble(key: String, default: Double = 0.0): Double =
    try { get(key)?.takeIf { it.isJsonPrimitive }?.asDouble ?: default } catch (_: Exception) { default }
private fun JsonObject.safeFloat(key: String, default: Float = 0f): Float =
    try { get(key)?.takeIf { it.isJsonPrimitive }?.asFloat ?: default } catch (_: Exception) { default }
private fun JsonObject.safeLong(key: String, default: Long = 0L): Long =
    try { get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: default } catch (_: Exception) { default }
import com.phynex.NexLink.model.*
import com.phynex.NexLink.service.LinkBridgeNotificationService
import com.phynex.NexLink.service.SmsReceiver
import com.phynex.NexLink.service.PairingManager
import com.phynex.NexLink.websocket.NexLinkSocketClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        @Volatile var instance: MainViewModel? = null
    }

    private val TAG = "MainViewModel"

    // ── Socket client ──────────────────────────────────────────────────────
    val socketClient = NexLinkSocketClient()

    // Expose as webSocket for backwards compat with screens that reference it
    val webSocket get() = socketClient

    // Connection State
    val isConnected: StateFlow<Boolean> = socketClient.isConnected
    val connectionMode: StateFlow<String> = socketClient.connectionMode

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // ── Firebase Auth ──────────────────────────────────────────────────────
    private val auth = FirebaseAuth.getInstance()
    private val _currentUser = MutableStateFlow<FirebaseUser?>(auth.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser

    private val _isSignedIn = MutableStateFlow(auth.currentUser != null)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn

    // ── Pairing ────────────────────────────────────────────────────────────
    private val pairingManager = PairingManager(application)

    private val _connectedDevice = MutableStateFlow<DeviceInfo?>(null)
    val connectedDevice: StateFlow<DeviceInfo?> = _connectedDevice

    // ── USB control ────────────────────────────────────────────────────────
    private val _isUsbMode = MutableStateFlow(false)
    val isUsbMode: StateFlow<Boolean> = _isUsbMode

    // ── PC info state ──────────────────────────────────────────────────────
    private val _wifiInfo = MutableStateFlow<WifiInfo?>(null)
    val wifiInfo: StateFlow<WifiInfo?> = _wifiInfo

    private val _batteryInfo = MutableStateFlow<BatteryInfo?>(null)
    val batteryInfo: StateFlow<BatteryInfo?> = _batteryInfo

    private val _bluetoothDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val bluetoothDevices: StateFlow<List<BluetoothDevice>> = _bluetoothDevices

    private val _wallpaperBase64 = MutableStateFlow<String?>(null)
    val wallpaperBase64: StateFlow<String?> = _wallpaperBase64

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private val _volume = MutableStateFlow(50)
    val volume: StateFlow<Int> = _volume

    private val _brightness = MutableStateFlow(50)
    val brightness: StateFlow<Int> = _brightness

    private val _appList = MutableStateFlow<List<AppItem>>(emptyList())
    val appList: StateFlow<List<AppItem>> = _appList

    private val _pinnedApps = MutableStateFlow<List<AppItem>>(emptyList())
    val pinnedApps: StateFlow<List<AppItem>> = _pinnedApps

    private val _currentPath = MutableStateFlow("root")
    val currentPath: StateFlow<String> = _currentPath

    private val _fileList = MutableStateFlow<List<FileItem>>(emptyList())
    val fileList: StateFlow<List<FileItem>> = _fileList

    private val _fileTransferProgress = MutableStateFlow<Float?>(null)
    val fileTransferProgress: StateFlow<Float?> = _fileTransferProgress

    private val _clipboardItems = MutableStateFlow<List<ClipboardItem>>(emptyList())
    val clipboardItems: StateFlow<List<ClipboardItem>> = _clipboardItems

    private val _smsThreads = MutableStateFlow<List<SmsThread>>(emptyList())
    val smsThreads: StateFlow<List<SmsThread>> = _smsThreads

    private val _currentThread = MutableStateFlow<List<SmsMessage>>(emptyList())
    val currentThread: StateFlow<List<SmsMessage>> = _currentThread

    private val _photos = MutableStateFlow<List<PhotoItem>>(emptyList())
    val photos: StateFlow<List<PhotoItem>> = _photos

    private val _screenFrameBase64 = MutableStateFlow<String?>(null)
    val screenFrameBase64: StateFlow<String?> = _screenFrameBase64

    private val _cameraFrameBase64 = MutableStateFlow<String?>(null)
    val cameraFrameBase64: StateFlow<String?> = _cameraFrameBase64

    private val _isStreamingScreen = MutableStateFlow(false)
    val isStreamingScreen: StateFlow<Boolean> = _isStreamingScreen

    private val _isStreamingCamera = MutableStateFlow(false)
    val isStreamingCamera: StateFlow<Boolean> = _isStreamingCamera

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage

    private var currentDownloadFile: FileOutputStream? = null
    private var currentDownloadName: String? = null

    // ──────────────────────────────────────────────────────────────────────
    init {
        instance = this
        observeConnection()
        setupSocketListeners()
        setupNotificationForwarding()
        setupSmsForwarding()
        observeFirebaseAuth()
        autoReconnect()
    }

    override fun onCleared() {
        super.onCleared()
        if (instance == this) instance = null
        socketClient.disconnect()
    }

    // ─── Auth ──────────────────────────────────────────────────────────────

    private fun observeFirebaseAuth() {
        auth.addAuthStateListener { fa ->
            _currentUser.value = fa.currentUser
            _isSignedIn.value  = fa.currentUser != null
        }
    }

    /** Returns a fresh Firebase ID token and saves it for reconnect use */
    private fun refreshAndSaveToken(onToken: (String) -> Unit) {
        val user = auth.currentUser
        if (user == null) { onToken(""); return }
        user.getIdToken(true).addOnSuccessListener { result ->
            val token = result.token ?: ""
            pairingManager.saveIdToken(token)
            onToken(token)
        }.addOnFailureListener {
            Log.e(TAG, "Token refresh failed: ${it.message}")
            onToken(pairingManager.getSavedIdToken() ?: "")
        }
    }

    // ─── Auto-reconnect on app start ──────────────────────────────────────

    private fun autoReconnect() {
        if (!pairingManager.hasSavedPairing()) return
        val userId   = pairingManager.getSavedUserId()   ?: return
        val deviceId = pairingManager.getSavedDeviceId() ?: return
        val pairId   = pairingManager.getSavedPairId()   ?: ""
        val relay    = pairingManager.getSavedRelayUrl()
        val name     = pairingManager.getSavedDeviceName()

        val savedDevice = DeviceInfo(userId, deviceId, name, pairId, relay)
        Log.d(TAG, "Auto-reconnect: uid=$userId device=$deviceId")

        refreshAndSaveToken { token ->
            connectToPC(savedDevice, token)
        }
    }

    /** Called by foreground service when network becomes available */
    fun reconnectIfNeeded() {
        if (!socketClient.isConnected.value) {
            refreshAndSaveToken { token ->
                val device = _connectedDevice.value ?: return@refreshAndSaveToken
                socketClient.connect(
                    relay     = device.relayUrl,
                    uid       = device.userId,
                    did       = device.deviceId,
                    token     = token,
                )
            }
        }
    }

    // ─── Connect ───────────────────────────────────────────────────────────

    fun connectToPC(deviceInfo: DeviceInfo, idToken: String = "") {
        _connectedDevice.value   = deviceInfo
        _connectionState.value   = ConnectionState.CONNECTING

        pairingManager.savePairing(
            userId     = deviceInfo.userId,
            deviceId   = deviceInfo.deviceId,
            pairId     = deviceInfo.pairId,
            relayUrl   = deviceInfo.relayUrl,
            idToken    = idToken,
            deviceName = deviceInfo.deviceName,
        )

        val doConnect: (String) -> Unit = { token ->
            socketClient.connect(
                relay = deviceInfo.relayUrl,
                uid   = deviceInfo.userId,
                did   = deviceInfo.deviceId,
                token = token,
            )
        }

        if (idToken.isNotEmpty()) {
            doConnect(idToken)
        } else {
            refreshAndSaveToken(doConnect)
        }

        // Request initial PC data once connected
        viewModelScope.launch {
            socketClient.isConnected.collect { connected ->
                if (connected) {
                    requestInitialData()
                    return@collect
                }
            }
        }
    }

    fun unpairAndDisconnect() {
        pairingManager.clearPairing()
        socketClient.disconnect()
        _connectedDevice.value   = null
        _connectionState.value   = ConnectionState.DISCONNECTED
    }

    // ─── Connection observation ────────────────────────────────────────────

    private fun observeConnection() {
        viewModelScope.launch {
            socketClient.isConnected.collect { connected ->
                _connectionState.value = if (connected) ConnectionState.CONNECTED
                                         else          ConnectionState.DISCONNECTED
            }
        }
    }

    // ─── Socket event listeners ────────────────────────────────────────────

    private fun setupSocketListeners() {
        socketClient.addListener("wifi_info") { json ->
            _wifiInfo.value = WifiInfo(
                ssid     = json.safeStr("ssid", "Unknown"),
                strength = json.safeInt("strength")
            )
        }

        socketClient.addListener("battery_info") { json ->
            _batteryInfo.value = BatteryInfo(
                level      = json.safeInt("level"),
                isCharging = json.safeBool("isCharging")
            )
        }

        socketClient.addListener("bt_info") { json ->
            val devices = json.getAsJsonArray("devices")?.mapNotNull { d ->
                try {
                    val obj = d.asJsonObject
                    BluetoothDevice(
                        name    = obj.safeStr("name", "Unknown"),
                        address = obj.safeStr("address", ""),
                        type    = obj.safeStr("type", "Unknown")
                    )
                } catch (_: Exception) { null }
            } ?: emptyList()
            _bluetoothDevices.value = devices
        }

        socketClient.addListener("wallpaper") { json ->
            _wallpaperBase64.value = json.safeStr("data")
        }

        socketClient.addListener("volume") { json ->
            _volume.value = json.safeInt("level", _volume.value)
        }

        socketClient.addListener("brightness") { json ->
            _brightness.value = json.safeInt("level", _brightness.value)
        }

        socketClient.addListener("now_playing") { json ->
            _nowPlaying.value = NowPlaying(
                title          = json.safeStr("title", "Unknown"),
                artist         = json.safeStr("artist", "Unknown"),
                albumArtBase64 = json.safeStr("album_art_base64"),
                isPlaying      = json.safeBool("isPlaying"),
                position       = json.safeDouble("position"),
                duration       = json.safeDouble("duration")
            )
        }

        socketClient.addListener("app_list") { json ->
            val apps = json.getAsJsonArray("apps")?.mapNotNull { a ->
                try {
                    val obj = a.asJsonObject
                    AppItem(
                        name       = obj.safeStr("name", ""),
                        path       = obj.safeStr("path", ""),
                        iconBase64 = obj.safeStr("icon")
                    )
                } catch (_: Exception) { null }
            } ?: emptyList()
            _appList.value = apps
        }

        socketClient.addListener("file_list") { json ->
            val files = json.getAsJsonArray("files")?.mapNotNull { f ->
                try {
                    val obj = f.asJsonObject
                    FileItem(
                        name        = obj.safeStr("name", ""),
                        path        = obj.safeStr("path", ""),
                        size        = obj.safeLong("size"),
                        isDirectory = obj.safeBool("isDirectory"),
                        type        = obj.safeStr("type", "file")
                    )
                } catch (_: Exception) { null }
            } ?: emptyList()
            _fileList.value = files
        }

        socketClient.addListener("file_chunk") { json ->
            val name     = json.safeStr("name", "unknown")
            val progress = json.safeFloat("progress")
            val data     = json.safeStr("data", "")
            val index    = json.safeInt("index")

            _fileTransferProgress.value = progress
            try {
                if (index == 0) {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NexLink")
                    if (!dir.exists()) dir.mkdirs()
                    currentDownloadFile = FileOutputStream(File(dir, name))
                    currentDownloadName = name
                }
                currentDownloadFile?.write(Base64.decode(data, Base64.DEFAULT))
                if (progress >= 1f) {
                    currentDownloadFile?.close()
                    currentDownloadFile = null
                    _toastMessage.value = "Downloaded: $currentDownloadName"
                    _fileTransferProgress.value = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _toastMessage.value = "Download failed: ${e.message}"
                currentDownloadFile?.close()
                currentDownloadFile = null
                _fileTransferProgress.value = null
            }
        }

        socketClient.addListener("clipboard_pull")  { json -> addClipboard(json, "pc") }
        socketClient.addListener("clipboard_push")  { json -> addClipboard(json, "pc") }
        socketClient.addListener("clipboard_sync")  { json -> addClipboard(json, "pc") }

        socketClient.addListener("sms_list") { json ->
            val threads = json.getAsJsonArray("threads")?.mapNotNull { t ->
                try {
                    val obj = t.asJsonObject
                    SmsThread(
                        id            = obj.safeStr("id", ""),
                        contactName   = obj.safeStr("contactName", "Unknown"),
                        contactNumber = obj.safeStr("contactNumber", ""),
                        lastMessage   = obj.safeStr("lastMessage", ""),
                        timestamp     = obj.safeLong("timestamp"),
                        unread        = obj.safeInt("unread")
                    )
                } catch (_: Exception) { null }
            } ?: emptyList()
            _smsThreads.value = threads
        }

        socketClient.addListener("photo_list") { json ->
            val photos = json.getAsJsonArray("photos")?.mapNotNull { p ->
                try {
                    val obj = p.asJsonObject
                    PhotoItem(
                        name            = obj.safeStr("name", ""),
                        path            = obj.safeStr("path", ""),
                        thumbnailBase64 = obj.safeStr("thumbnail"),
                        timestamp       = obj.safeLong("timestamp")
                    )
                } catch (_: Exception) { null }
            } ?: emptyList()
            _photos.value = photos
        }

        socketClient.addListener("screen_frame") { json ->
            _screenFrameBase64.value = json.safeStr("data")
        }

        socketClient.addListener("camera_frame") { json ->
            _cameraFrameBase64.value = json.safeStr("data")
        }

        socketClient.addListener("usb_connected") { _ ->
            Log.d(TAG, "USB mode activated by laptop")
            _isUsbMode.value = true
        }

        socketClient.addListener("usb_disconnected") { _ ->
            Log.d(TAG, "USB mode deactivated")
            _isUsbMode.value = false
        }

        socketClient.addListener("error") { json ->
            _toastMessage.value = json.safeStr("message", "Unknown error")
        }
    }

    private fun addClipboard(json: JsonObject, source: String) {
        val content = json.safeStr("content") ?: return
        val item    = ClipboardItem(content = content, isImage = false, source = source)
        _clipboardItems.value = (_clipboardItems.value + item).takeLast(50)
    }

    // ─── Forwarding setup ──────────────────────────────────────────────────

    private fun setupNotificationForwarding() {
        LinkBridgeNotificationService.onNotification = { app, title, body ->
            socketClient.sendMessage(mapOf(
                "type"      to "notification",
                "app"       to app,
                "title"     to title,
                "body"      to body,
                "timestamp" to System.currentTimeMillis()
            ))
        }
    }

    private fun setupSmsForwarding() {
        SmsReceiver.onSmsReceived = { sender, body, timestamp ->
            socketClient.sendMessage(mapOf(
                "type"      to "sms_received",
                "sender"    to sender,
                "body"      to body,
                "timestamp" to timestamp
            ))
        }
    }

    // ─── Commands ──────────────────────────────────────────────────────────

    fun requestInitialData() {
        socketClient.sendMessage(mapOf("type" to "request_info"))
        socketClient.sendMessage(mapOf("type" to "get_wallpaper"))
    }

    fun requestSystemInfo() = socketClient.sendMessage(mapOf("type" to "request_info"))

    fun sendMediaControl(action: String) =
        socketClient.sendMessage(mapOf("type" to "media_control", "action" to action))

    fun seekMedia(positionSec: Double) {
        socketClient.sendMessage(mapOf("type" to "media_seek", "position" to positionSec))
        _nowPlaying.value = _nowPlaying.value?.copy(position = positionSec)
    }

    fun sendVolume(level: Int) {
        _volume.value = level
        socketClient.sendMessage(mapOf("type" to "volume", "level" to level))
    }

    fun sendBrightness(level: Int) {
        _brightness.value = level
        socketClient.sendMessage(mapOf("type" to "brightness", "level" to level))
    }

    fun lockPC() = socketClient.sendMessage(mapOf("type" to "lock_pc"))

    fun launchApp(appName: String, appPath: String) =
        socketClient.sendMessage(mapOf("type" to "launch_app", "appName" to appName, "appPath" to appPath))

    fun requestAppList() = socketClient.sendMessage(mapOf("type" to "app_list"))

    fun browsePath(path: String) {
        _currentPath.value = path
        socketClient.sendMessage(mapOf("type" to "browse", "path" to path))
    }

    fun openFile(path: String) = socketClient.sendMessage(mapOf("type" to "open_file", "path" to path))

    fun downloadFile(path: String) = socketClient.sendMessage(mapOf("type" to "download_file", "path" to path))

    fun pushClipboard(content: String) {
        _clipboardItems.value = (_clipboardItems.value + ClipboardItem(content = content, source = "phone")).takeLast(50)
        socketClient.sendMessage(mapOf("type" to "clipboard_push", "content" to content))
    }

    fun pullClipboard() = socketClient.sendMessage(mapOf("type" to "clipboard_pull"))

    fun startScreenStream() {
        _isStreamingScreen.value = true
        socketClient.sendMessage(mapOf("type" to "start_screen"))
    }

    fun stopScreenStream() {
        _isStreamingScreen.value = false
        socketClient.sendMessage(mapOf("type" to "stop_screen"))
    }

    fun startCameraStream() {
        _isStreamingCamera.value = true
        socketClient.sendMessage(mapOf("type" to "start_camera"))
    }

    fun stopCameraStream() {
        _isStreamingCamera.value = false
        socketClient.sendMessage(mapOf("type" to "stop_camera"))
    }

    fun pinApp(app: AppItem) {
        if (!_pinnedApps.value.any { it.name == app.name }) {
            _pinnedApps.value = _pinnedApps.value + app
        }
    }

    fun clearToast() { _toastMessage.value = null }

    fun sendSms(threadId: String, body: String) =
        socketClient.sendMessage(mapOf("type" to "sms_send", "threadId" to threadId, "body" to body))

    // ─── USB / touchpad events ─────────────────────────────────────────────

    fun onUsbConnected() {
        _isUsbMode.value = true
        socketClient.sendMessage(mapOf("type" to "usb_connected"))
    }

    fun onUsbDisconnected() {
        _isUsbMode.value = false
        socketClient.sendMessage(mapOf("type" to "usb_disconnected"))
    }

    fun sendMouseMove(dx: Float, dy: Float) {
        socketClient.sendMessage(mapOf("type" to "mouse_move", "dx" to dx, "dy" to dy))
    }

    fun sendMouseTap() {
        socketClient.sendMessage(mapOf("type" to "mouse_tap"))
    }

    fun sendMouseRightTap() {
        socketClient.sendMessage(mapOf("type" to "mouse_right_tap"))
    }

    fun sendMouseScroll(dy: Float) {
        socketClient.sendMessage(mapOf("type" to "mouse_scroll", "dy" to dy))
    }
}
