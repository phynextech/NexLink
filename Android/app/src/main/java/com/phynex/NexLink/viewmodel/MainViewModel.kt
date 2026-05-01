package com.phynex.NexLink.viewmodel

import android.app.Application
import android.content.Context
import android.os.Environment
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.phynex.NexLink.model.*
import com.phynex.NexLink.service.LinkBridgeNotificationService
import com.phynex.NexLink.service.SmsReceiver
import com.phynex.NexLink.websocket.LinkBridgeWebSocket
import com.phynex.NexLink.service.PairingManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        // Shared instance accessible by foreground service for reconnect triggering
        @Volatile var instance: MainViewModel? = null
    }

    private val TAG = "MainViewModel"
    val webSocket = LinkBridgeWebSocket()

    // Connection State
    val isConnected: StateFlow<Boolean> = webSocket.isConnected
    val connectionMode: StateFlow<String> = webSocket.connectionMode
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val pairingManager = PairingManager(application)

    // Device info from QR
    private val _connectedDevice = MutableStateFlow<DeviceInfo?>(null)
    val connectedDevice: StateFlow<DeviceInfo?> = _connectedDevice

    // PC info
    private val _wifiInfo = MutableStateFlow<WifiInfo?>(null)
    val wifiInfo: StateFlow<WifiInfo?> = _wifiInfo

    private val _batteryInfo = MutableStateFlow<BatteryInfo?>(null)
    val batteryInfo: StateFlow<BatteryInfo?> = _batteryInfo

    private val _bluetoothDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val bluetoothDevices: StateFlow<List<BluetoothDevice>> = _bluetoothDevices

    private val _wallpaperBase64 = MutableStateFlow<String?>(null)
    val wallpaperBase64: StateFlow<String?> = _wallpaperBase64

    // Music
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private val _volume = MutableStateFlow(50)
    val volume: StateFlow<Int> = _volume

    private val _brightness = MutableStateFlow(50)
    val brightness: StateFlow<Int> = _brightness

    // Apps
    private val _appList = MutableStateFlow<List<AppItem>>(emptyList())
    val appList: StateFlow<List<AppItem>> = _appList

    private val _pinnedApps = MutableStateFlow<List<AppItem>>(emptyList())
    val pinnedApps: StateFlow<List<AppItem>> = _pinnedApps

    // Files
    private val _currentPath = MutableStateFlow("root")
    val currentPath: StateFlow<String> = _currentPath

    private val _fileList = MutableStateFlow<List<FileItem>>(emptyList())
    val fileList: StateFlow<List<FileItem>> = _fileList

    private val _fileTransferProgress = MutableStateFlow<Float?>(null)
    val fileTransferProgress: StateFlow<Float?> = _fileTransferProgress

    // Clipboard
    private val _clipboardItems = MutableStateFlow<List<ClipboardItem>>(emptyList())
    val clipboardItems: StateFlow<List<ClipboardItem>> = _clipboardItems

    // SMS
    private val _smsThreads = MutableStateFlow<List<SmsThread>>(emptyList())
    val smsThreads: StateFlow<List<SmsThread>> = _smsThreads

    private val _currentThread = MutableStateFlow<List<SmsMessage>>(emptyList())
    val currentThread: StateFlow<List<SmsMessage>> = _currentThread

    // Photos
    private val _photos = MutableStateFlow<List<PhotoItem>>(emptyList())
    val photos: StateFlow<List<PhotoItem>> = _photos

    // Screen/Camera streams
    private val _screenFrameBase64 = MutableStateFlow<String?>(null)
    val screenFrameBase64: StateFlow<String?> = _screenFrameBase64

    private val _cameraFrameBase64 = MutableStateFlow<String?>(null)
    val cameraFrameBase64: StateFlow<String?> = _cameraFrameBase64

    private val _isStreamingScreen = MutableStateFlow(false)
    val isStreamingScreen: StateFlow<Boolean> = _isStreamingScreen

    private val _isStreamingCamera = MutableStateFlow(false)
    val isStreamingCamera: StateFlow<Boolean> = _isStreamingCamera

    // Error / Toast messages
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage

    private var currentDownloadFile: FileOutputStream? = null
    private var currentDownloadName: String? = null

    init {
        instance = this
        observeConnection()
        setupWebSocketListeners()
        setupNotificationForwarding()
        setupSmsForwarding()
        
        // Auto-connect if we have a saved pairing
        if (pairingManager.hasSavedPairing()) {
            val pairId = pairingManager.getSavedPairId() ?: ""
            val ip = pairingManager.getSavedIp() ?: ""
            val port = pairingManager.getSavedPort()
            val relayUrl = pairingManager.getSavedRelayUrl() ?: ""
            val token = pairingManager.getSavedToken() ?: ""
            
            val savedDevice = DeviceInfo(ip, port, "Paired PC", token, pairId, relayUrl)
            connectToPC(savedDevice)
        }
    }


    override fun onCleared() {
        super.onCleared()
        if (instance == this) instance = null
        webSocket.disconnect()
    }

    /** Called by foreground service when WiFi becomes available */
    fun reconnectIfNeeded() {
        val device = _connectedDevice.value ?: return
        if (!webSocket.isConnected.value) {
            Log.d(TAG, "Auto-reconnecting to ${device.ip}:${device.port}")
            webSocket.connect(device.ip, device.port, device.sessionToken)
        }
    }

    private fun observeConnection() {
        viewModelScope.launch {
            webSocket.isConnected.collect { connected ->
                _connectionState.value = if (connected) ConnectionState.CONNECTED
                else ConnectionState.DISCONNECTED
            }
        }
    }

    private fun setupWebSocketListeners() {
        webSocket.addListener("wifi_info") { json ->
            _wifiInfo.value = WifiInfo(
                ssid = json.get("ssid")?.asString ?: "Unknown",
                strength = json.get("strength")?.asInt ?: 0
            )
        }

        webSocket.addListener("battery_info") { json ->
            _batteryInfo.value = BatteryInfo(
                level = json.get("level")?.asInt ?: 0,
                isCharging = json.get("isCharging")?.asBoolean ?: false
            )
        }

        webSocket.addListener("bt_info") { json ->
            val devices = json.getAsJsonArray("devices")?.map { d ->
                val obj = d.asJsonObject
                BluetoothDevice(
                    name = obj.get("name")?.asString ?: "Unknown",
                    address = obj.get("address")?.asString ?: "",
                    type = obj.get("type")?.asString ?: "Unknown"
                )
            } ?: emptyList()
            _bluetoothDevices.value = devices
        }

        webSocket.addListener("wallpaper") { json ->
            _wallpaperBase64.value = json.get("data")?.asString
        }

        webSocket.addListener("now_playing") { json ->
            _nowPlaying.value = NowPlaying(
                title = json.get("title")?.asString ?: "Unknown",
                artist = json.get("artist")?.asString ?: "Unknown",
                albumArtBase64 = json.get("album_art_base64")?.asString,
                isPlaying = json.get("isPlaying")?.asBoolean ?: false
            )
        }

        webSocket.addListener("app_list") { json ->
            val apps = json.getAsJsonArray("apps")?.map { a ->
                val obj = a.asJsonObject
                AppItem(
                    name = obj.get("name")?.asString ?: "",
                    path = obj.get("path")?.asString ?: "",
                    iconBase64 = obj.get("icon")?.asString
                )
            } ?: emptyList()
            _appList.value = apps
        }

        webSocket.addListener("file_list") { json ->
            val files = json.getAsJsonArray("files")?.map { f ->
                val obj = f.asJsonObject
                FileItem(
                    name = obj.get("name")?.asString ?: "",
                    path = obj.get("path")?.asString ?: "",
                    size = obj.get("size")?.asLong ?: 0L,
                    isDirectory = obj.get("isDirectory")?.asBoolean ?: false,
                    type = obj.get("type")?.asString ?: "file"
                )
            } ?: emptyList()
            _fileList.value = files
        }

        webSocket.addListener("file_chunk") { json ->
            val name = json.get("name")?.asString ?: "unknown"
            val progress = json.get("progress")?.asFloat ?: 0f
            val data = json.get("data")?.asString ?: ""
            val index = json.get("index")?.asInt ?: 0
            
            _fileTransferProgress.value = progress
            
            try {
                if (index == 0) {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NexLink")
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, name)
                    currentDownloadFile = FileOutputStream(file)
                    currentDownloadName = name
                }
                
                val bytes = Base64.decode(data, Base64.DEFAULT)
                currentDownloadFile?.write(bytes)
                
                if (progress >= 1f) {
                    currentDownloadFile?.close()
                    currentDownloadFile = null
                    _toastMessage.value = "Downloaded: " + currentDownloadName
                    _fileTransferProgress.value = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _toastMessage.value = "Download failed: " + e.message
                currentDownloadFile?.close()
                currentDownloadFile = null
                _fileTransferProgress.value = null
            }
        }

        webSocket.addListener("clipboard_pull") { json ->
            val content = json.get("content")?.asString ?: return@addListener
            val item = ClipboardItem(content = content, isImage = false, source = "pc")
            _clipboardItems.value = (_clipboardItems.value + item).takeLast(50)
        }

        webSocket.addListener("clipboard_push") { json ->
            // PC is pushing clipboard to phone
            val content = json.get("content")?.asString ?: return@addListener
            val item = ClipboardItem(content = content, isImage = false, source = "pc")
            _clipboardItems.value = (_clipboardItems.value + item).takeLast(50)
        }

        webSocket.addListener("sms_list") { json ->
            val threads = json.getAsJsonArray("threads")?.map { t ->
                val obj = t.asJsonObject
                SmsThread(
                    id = obj.get("id")?.asString ?: "",
                    contactName = obj.get("contactName")?.asString ?: "Unknown",
                    contactNumber = obj.get("contactNumber")?.asString ?: "",
                    lastMessage = obj.get("lastMessage")?.asString ?: "",
                    timestamp = obj.get("timestamp")?.asLong ?: 0L,
                    unread = obj.get("unread")?.asInt ?: 0
                )
            } ?: emptyList()
            _smsThreads.value = threads
        }

        webSocket.addListener("photo_list") { json ->
            val photos = json.getAsJsonArray("photos")?.map { p ->
                val obj = p.asJsonObject
                PhotoItem(
                    name = obj.get("name")?.asString ?: "",
                    path = obj.get("path")?.asString ?: "",
                    thumbnailBase64 = obj.get("thumbnail")?.asString,
                    timestamp = obj.get("timestamp")?.asLong ?: 0L
                )
            } ?: emptyList()
            _photos.value = photos
        }

        webSocket.addListener("screen_frame") { json ->
            _screenFrameBase64.value = json.get("data")?.asString
        }

        webSocket.addListener("camera_frame") { json ->
            _cameraFrameBase64.value = json.get("data")?.asString
        }

        webSocket.addListener("error") { json ->
            _toastMessage.value = json.get("message")?.asString ?: "Unknown error"
        }
    }

    private fun setupNotificationForwarding() {
        LinkBridgeNotificationService.onNotification = { app, title, body ->
            webSocket.sendMessage(mapOf(
                "type" to "notification",
                "app" to app,
                "title" to title,
                "body" to body,
                "timestamp" to System.currentTimeMillis()
            ))
        }
    }

    private fun setupSmsForwarding() {
        SmsReceiver.onSmsReceived = { sender, body, timestamp ->
            webSocket.sendMessage(mapOf(
                "type" to "sms_received",
                "sender" to sender,
                "body" to body,
                "timestamp" to timestamp
            ))
        }
    }

    fun connectToPC(deviceInfo: DeviceInfo) {
        _connectedDevice.value = deviceInfo
        _connectionState.value = ConnectionState.CONNECTING
        
        // Save this pairing for future auto-connects
        pairingManager.savePairing(
            pairId = deviceInfo.pairId,
            ip = deviceInfo.ip,
            port = deviceInfo.port,
            relayUrl = deviceInfo.relayUrl,
            token = deviceInfo.sessionToken
        )
        
        // Pass pairId and relayUrl so WebSocket can fall back to relay
        webSocket.connect(
            ip = deviceInfo.ip,
            port = deviceInfo.port,
            token = deviceInfo.sessionToken,
            pair = deviceInfo.pairId,
            relay = deviceInfo.relayUrl
        )

        // Request initial data once connected
        viewModelScope.launch {
            webSocket.isConnected.collect { connected ->
                if (connected) {
                    requestInitialData()
                    return@collect
                }
            }
        }
    }

    fun unpairAndDisconnect() {
        pairingManager.clearPairing()
        webSocket.disconnect()
        _connectedDevice.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun requestInitialData() {
        webSocket.sendMessage(mapOf("type" to "request_info"))
        webSocket.sendMessage(mapOf("type" to "get_wallpaper"))
    }

    fun requestSystemInfo() {
        webSocket.sendMessage(mapOf("type" to "request_info"))
    }

    fun sendMediaControl(action: String) {
        webSocket.sendMessage(mapOf("type" to "media_control", "action" to action))
    }

    fun sendVolume(level: Int) {
        _volume.value = level
        webSocket.sendMessage(mapOf("type" to "volume", "level" to level))
    }

    fun sendBrightness(level: Int) {
        _brightness.value = level
        webSocket.sendMessage(mapOf("type" to "brightness", "level" to level))
    }

    fun lockPC() {
        webSocket.sendMessage(mapOf("type" to "lock_pc"))
    }

    fun launchApp(appName: String, appPath: String) {
        webSocket.sendMessage(mapOf("type" to "launch_app", "appName" to appName, "appPath" to appPath))
    }

    fun requestAppList() {
        webSocket.sendMessage(mapOf("type" to "app_list"))
    }

    fun browsePath(path: String) {
        _currentPath.value = path
        webSocket.sendMessage(mapOf("type" to "browse", "path" to path))
    }

    fun openFile(path: String) {
        webSocket.sendMessage(mapOf("type" to "open_file", "path" to path))
    }

    fun downloadFile(path: String) {
        webSocket.sendMessage(mapOf("type" to "download_file", "path" to path))
    }

    fun pushClipboard(content: String) {
        val item = ClipboardItem(content = content, isImage = false, source = "phone")
        _clipboardItems.value = (_clipboardItems.value + item).takeLast(50)
        webSocket.sendMessage(mapOf("type" to "clipboard_push", "content" to content))
    }

    fun pullClipboard() {
        webSocket.sendMessage(mapOf("type" to "clipboard_pull"))
    }

    fun startScreenStream() {
        _isStreamingScreen.value = true
        webSocket.sendMessage(mapOf("type" to "start_screen"))
    }

    fun stopScreenStream() {
        _isStreamingScreen.value = false
        webSocket.sendMessage(mapOf("type" to "stop_screen"))
    }

    fun startCameraStream() {
        _isStreamingCamera.value = true
        webSocket.sendMessage(mapOf("type" to "start_camera"))
    }

    fun stopCameraStream() {
        _isStreamingCamera.value = false
        webSocket.sendMessage(mapOf("type" to "stop_camera"))
    }

    fun pinApp(app: AppItem) {
        if (!_pinnedApps.value.any { it.name == app.name }) {
            _pinnedApps.value = _pinnedApps.value + app
        }
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun sendSms(threadId: String, body: String) {
        webSocket.sendMessage(mapOf("type" to "sms_send", "threadId" to threadId, "body" to body))
    }

}
