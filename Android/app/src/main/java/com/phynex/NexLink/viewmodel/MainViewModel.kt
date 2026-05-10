package com.phynex.NexLink.viewmodel

import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Environment
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.gson.JsonElement
import com.google.gson.JsonObject

import com.phynex.NexLink.MainActivity
import com.phynex.NexLink.model.*
import com.phynex.NexLink.service.LinkBridgeNotificationService
import com.phynex.NexLink.service.SmsReceiver
import com.phynex.NexLink.service.PairingManager
import com.phynex.NexLink.websocket.NexLinkSocketClient
import com.phynex.NexLink.webrtc.WebRtcManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream
import android.database.ContentObserver
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification

// ── Safe JSON helpers (guard against JsonArray instead of JsonPrimitive and handle PascalCase) ───
private fun JsonObject.safeGet(key: String) = get(key) ?: get(key.replaceFirstChar { it.uppercase() })
private fun JsonObject.safeStr(key: String, default: String = ""): String =
    try { safeGet(key)?.takeIf { it.isJsonPrimitive }?.asString ?: default } catch (_: Exception) { default }
private fun JsonObject.safeStr(key: String): String? =
    try { safeGet(key)?.takeIf { it.isJsonPrimitive }?.asString } catch (_: Exception) { null }
private fun JsonObject.safeInt(key: String, default: Int = 0): Int =
    try { safeGet(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: default } catch (_: Exception) { default }
private fun JsonObject.safeBool(key: String, default: Boolean = false): Boolean =
    try { safeGet(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: default } catch (_: Exception) { default }
private fun JsonObject.safeDouble(key: String, default: Double = 0.0): Double =
    try { safeGet(key)?.takeIf { it.isJsonPrimitive }?.asDouble ?: default } catch (_: Exception) { default }
private fun JsonObject.safeFloat(key: String, default: Float = 0f): Float =
    try { safeGet(key)?.takeIf { it.isJsonPrimitive }?.asFloat ?: default } catch (_: Exception) { default }
private fun JsonObject.safeLong(key: String, default: Long = 0L): Long =
    try { safeGet(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: default } catch (_: Exception) { default }


class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        @Volatile var instance: MainViewModel? = null
    }

    private val TAG = "MainViewModel"

    // ── Socket client ──────────────────────────────────────────────────────
    val socketClient = NexLinkSocketClient()

    // Expose as webSocket for backwards compat with screens that reference it
    val webSocket get() = socketClient
    
    // WebRTC
    val webRtcManager = WebRtcManager(application, socketClient)

    // Connection State
    val isConnected: StateFlow<Boolean> = socketClient.isConnected
    val isPeerOnline: StateFlow<Boolean> = socketClient.isPeerOnline
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

    private val _volume = MutableStateFlow(0)
    val volume: StateFlow<Int> = _volume

    private val _brightness = MutableStateFlow(0)
    val brightness: StateFlow<Int> = _brightness

    // Track what we last sent to PC so we can distinguish echoes from real PC-side changes
    @Volatile private var _lastSentVolume = -1
    @Volatile private var _lastSentBrightness = -1

    // OSD trigger flows (emit true when value changes externally from PC)
    private val _volumeOsdTrigger = MutableStateFlow<Int?>(null)
    val volumeOsdTrigger: StateFlow<Int?> = _volumeOsdTrigger

    private val _brightnessOsdTrigger = MutableStateFlow<Int?>(null)
    val brightnessOsdTrigger: StateFlow<Int?> = _brightnessOsdTrigger

    private val _bluetoothEnabled = MutableStateFlow(false)
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted

    private val _deviceName = MutableStateFlow("Windows PC")
    val deviceName: StateFlow<String> = _deviceName

    private val _osVersion = MutableStateFlow("Windows")
    val osVersion: StateFlow<String> = _osVersion

    private val _filePreviewData = MutableStateFlow<Pair<String, String>?>(null) // path to data
    val filePreviewData: StateFlow<Pair<String, String>?> = _filePreviewData

    private val _appList = MutableStateFlow<List<AppItem>>(emptyList())
    val appList: StateFlow<List<AppItem>> = _appList

    private val _pinnedApps = MutableStateFlow<List<AppItem>>(emptyList())
    val pinnedApps: StateFlow<List<AppItem>> = _pinnedApps

    private val _runningApps = MutableStateFlow<List<AppItem>>(emptyList())
    val runningApps: StateFlow<List<AppItem>> = _runningApps

    private val _performance = MutableStateFlow(PerformanceMetrics())
    val performance: StateFlow<PerformanceMetrics> = _performance

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

    // ── Mobile-side status (sent to Windows in real-time) ──────────────────
    private val _mobileStatus = MutableStateFlow(MobileStatus())
    val mobileStatus: StateFlow<MobileStatus> = _mobileStatus

    private var audioTrack: AudioTrack? = null

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage

    private var currentDownloadFile: FileOutputStream? = null
    private var currentDownloadName: String? = null

    // ── Camera Streaming State ──────────────────────────────────────────────
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraExecutor: ExecutorService? = null
    private var isCameraStreaming = false

    // ── Status Observers ───────────────────────────────────────────────────
    private var batteryReceiver: BroadcastReceiver? = null
    private var volumeObserver: ContentObserver? = null

    private val prefs = application.getSharedPreferences("NexLinkPrefs", android.content.Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(prefs.getString("theme_mode", "System") ?: "System")
    val themeMode: StateFlow<String> = _themeMode

    private val _primaryColor = MutableStateFlow(prefs.getString("primary_color", "Monochrome") ?: "Monochrome")
    val primaryColor: StateFlow<String> = _primaryColor

    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode).apply()
    }

    fun setPrimaryColor(color: String) {
        _primaryColor.value = color
        prefs.edit().putString("primary_color", color).apply()
    }

    // ──────────────────────────────────────────────────────────────────────
    init {
        instance = this
        observeConnection()
        setupSocketListeners()
        setupNotificationForwarding()
        setupSmsForwarding()
        observeFirebaseAuth()
        autoReconnect()
        setupStatusObservers()
    }

    private fun setupStatusObservers() {
        // 1. Battery Observer
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                sendSystemInfoToPC()
            }
        }
        getApplication<Application>().registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // 2. Volume Observer
        volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                sendSystemInfoToPC()
            }
        }
        getApplication<Application>().contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI, true, volumeObserver!!
        )
    }

    override fun onCleared() {
        super.onCleared()
        if (instance == this) instance = null
        batteryReceiver?.let { try { getApplication<Application>().unregisterReceiver(it) } catch (_: Exception) {} }
        volumeObserver?.let { getApplication<Application>().contentResolver.unregisterContentObserver(it) }
        stopMobileCameraStream()
        cameraExecutor?.shutdown()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
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

        // Request initial PC data once connected — use `first` so this coroutine
        // auto-cancels after the first true emission instead of leaking a collector.
        viewModelScope.launch {
            socketClient.isConnected.first { it }
            requestInitialData()
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
            combine(socketClient.isConnected, socketClient.isPeerOnline) { relay, peer ->
                relay && peer
            }.collect { connected ->
                _connectionState.value = if (connected) ConnectionState.CONNECTED
                                         else          ConnectionState.DISCONNECTED
            }
        }
    }

    // ─── Socket event listeners ────────────────────────────────────────────

    private fun setupSocketListeners() {
        socketClient.addListener("wifi_info") { json ->
            _wifiInfo.value = WifiInfo(
                ssid      = json.safeStr("ssid", "Unknown"),
                strength  = json.safeInt("strength"),
                connected = json.safeBool("connected")
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
            _bluetoothEnabled.value = json.safeBool("bluetoothEnabled", devices.isNotEmpty())
        }

        socketClient.addListener("wallpaper") { json ->
            _wallpaperBase64.value = json.safeStr("data")
        }

        socketClient.addListener("volume") { json ->
            val newVol = json.safeInt("level", _volume.value)
            val old = _volume.value
            _volume.value = newVol
            // Only show OSD when value is pushed by PC (not an ack of our own send)
            if (newVol != old) _volumeOsdTrigger.value = newVol
        }

        // volume_ack: PC confirmed our slider change — silently sync, no OSD
        socketClient.addListener("volume_ack") { json ->
            _volume.value = json.safeInt("level", _volume.value)
        }

        socketClient.addListener("brightness") { json ->
            val newBri = json.safeInt("level", _brightness.value)
            val old = _brightness.value
            _brightness.value = newBri
            if (newBri != old) _brightnessOsdTrigger.value = newBri
        }

        // brightness_ack: PC confirmed our slider change — silently sync, no OSD
        socketClient.addListener("brightness_ack") { json ->
            _brightness.value = json.safeInt("level", _brightness.value)
        }

        // system_state: full initial state from PC on connect — fan out to all flows
        socketClient.addListener("system_state") { json ->
            // WiFi
            json.getAsJsonObject("wifi")?.let { wifi ->
                _wifiInfo.value = WifiInfo(
                    ssid      = wifi.safeStr("ssid", "Unknown"),
                    strength  = wifi.safeInt("strength"),
                    connected = wifi.safeBool("connected")
                )
            }
            // Battery
            json.getAsJsonObject("battery")?.let { bat ->
                _batteryInfo.value = BatteryInfo(
                    level      = bat.safeInt("percentage"),
                    isCharging = bat.safeBool("charging")
                )
            }
            // Bluetooth
            json.getAsJsonObject("bluetooth")?.let { bt ->
                _bluetoothEnabled.value = bt.safeBool("enabled")
                val devList = bt.getAsJsonArray("connectedDevices")?.mapNotNull { d ->
                    try {
                        val obj = d.asJsonObject
                        BluetoothDevice(
                            name    = obj.safeStr("name", "Unknown"),
                            address = obj.safeStr("address", ""),
                            type    = obj.safeStr("type", "Bluetooth")
                        )
                    } catch (_: Exception) { null }
                } ?: emptyList()
                _bluetoothDevices.value = devList
            }
            // Volume / brightness / muted — set silently (no OSD on initial connect)
            _volume.value     = json.safeInt("volume", _volume.value)
            _brightness.value = json.safeInt("brightness", _brightness.value)
            _muted.value      = json.safeBool("muted")
            
        }

        socketClient.addListener("webrtc_offer") { json ->
            json.safeStr("sdp")?.let { webRtcManager.handleOffer(it) }
        }
        socketClient.addListener("webrtc_answer") { json ->
            json.safeStr("sdp")?.let { webRtcManager.handleAnswer(it) }
        }
        socketClient.addListener("webrtc_ice") { json ->
            val candidateStr = json.safeStr("candidate") ?: json.getAsJsonObject("candidate")?.toString()
            candidateStr?.let { webRtcManager.handleIceCandidate(it) }
        }

        // system_state: full initial state from PC on connect — fan out to all flows
        socketClient.addListener("system_state") { json ->
            // Wallpaper
            val wall = json.safeStr("wallpaper")
            if (!wall.isNullOrEmpty()) _wallpaperBase64.value = wall
            // Device meta
            val dn = json.safeStr("deviceName", "")
            if (dn.isNotEmpty()) _deviceName.value = dn
            val ov = json.safeStr("osVersion", "")
            if (ov.isNotEmpty()) _osVersion.value = ov
        }

        // state_update: lightweight 2s refresh
        socketClient.addListener("state_update") { json ->
            // Bluetooth
            json.getAsJsonObject("bluetooth")?.let { bt ->
                _bluetoothEnabled.value = bt.safeBool("enabled", _bluetoothEnabled.value)
                val devList = bt.getAsJsonArray("connectedDevices")?.mapNotNull { d ->
                    try {
                        val obj = d.asJsonObject
                        BluetoothDevice(
                            name    = obj.safeStr("name", "Unknown"),
                            address = obj.safeStr("address", ""),
                            type    = obj.safeStr("type", "Bluetooth")
                        )
                    } catch (_: Exception) { null }
                }
                if (devList != null) {
                    _bluetoothDevices.value = devList
                }
            }
            // WiFi
            json.getAsJsonObject("wifi")?.let { wifi ->
                _wifiInfo.value = WifiInfo(
                    ssid      = wifi.safeStr("ssid", _wifiInfo.value?.ssid ?: "Unknown"),
                    strength  = wifi.safeInt("strength", _wifiInfo.value?.strength ?: 0),
                    connected = wifi.safeBool("connected", _wifiInfo.value?.connected ?: false)
                )
            }
            // Battery
            json.getAsJsonObject("battery")?.let { bat ->
                _batteryInfo.value = BatteryInfo(
                    level      = bat.safeInt("percentage", _batteryInfo.value?.level ?: 0),
                    isCharging = bat.safeBool("charging", _batteryInfo.value?.isCharging ?: false)
                )
            }
            // Volume — show OSD only if PC changed it (not echoing our own send)
            val newVol = json.safeInt("volume", _volume.value)
            if (newVol != _volume.value) {
                _volume.value = newVol
                // Only show OSD if this wasn't caused by us sending it
                if (newVol != _lastSentVolume) _volumeOsdTrigger.value = newVol
                _lastSentVolume = -1  // reset after processing
            }
            // Brightness — show OSD only if PC changed it
            val newBri = json.safeInt("brightness", _brightness.value)
            if (newBri != _brightness.value) {
                _brightness.value = newBri
                if (newBri != _lastSentBrightness) _brightnessOsdTrigger.value = newBri
                _lastSentBrightness = -1
            }
            _muted.value = json.safeBool("muted", _muted.value)

            // Tunnel running_apps through state_update to bypass Render event filtering
            json.getAsJsonArray("running_apps")?.let { arr ->
                val apps = arr.mapNotNull { a ->
                    try {
                        val obj = a.asJsonObject
                        AppItem(
                            name       = obj.safeStr("Name", obj.safeStr("name", "")),
                            path       = obj.safeStr("Path", obj.safeStr("path", "")),
                            iconBase64 = obj.safeStr("IconBase64", obj.safeStr("icon", "")),
                            category   = obj.safeStr("Category", obj.safeStr("category", "Desktop 1")),
                            handle     = obj.safeStr("Handle", obj.safeStr("handle", "")),
                            isForeground = obj.safeBool("IsForeground", false)
                        )
                    } catch (_: Exception) { null }
                }
                _runningApps.value = apps
            }

            json.getAsJsonObject("performance")?.let { perf ->
                _performance.value = PerformanceMetrics(
                    cpu = perf.safeInt("cpu", -1),
                    gpu = perf.safeInt("gpu", -1),
                    ram = perf.safeInt("ram", -1),
                    vram = perf.safeInt("vram", -1),
                    fps = perf.safeInt("fps", -1),
                    wifi = perf.safeInt("wifi", -1)
                )
            }
        }

        socketClient.addListener("now_playing") { json ->
            _nowPlaying.value = NowPlaying(
                title          = json.safeStr("title", "Unknown"),
                artist         = json.safeStr("artist", "Unknown"),
                albumArtBase64 = json.safeStr("album_art_base64"),
                isPlaying      = json.safeBool("isPlaying"),
                position       = json.safeDouble("position"),
                duration       = json.safeDouble("duration"),
                appSource      = json.safeStr("appSource", ""),
                shuffleActive  = json.safeBool("shuffleActive"),
                repeatMode     = json.safeInt("repeatMode")
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

        // (Removed running_apps dedicated listener as we tunnel it through state_update now)

        socketClient.addListener("file_list") { json ->
            val files = json.getAsJsonArray("files")?.mapNotNull { f ->
                try {
                    val obj = f.asJsonObject
                    FileItem(
                        name            = obj.safeStr("name", ""),
                        path            = obj.safeStr("path", ""),
                        size            = obj.safeLong("size"),
                        isDirectory     = obj.safeBool("isDirectory"),
                        type            = obj.safeStr("type", "file"),
                        thumbnailBase64 = obj.safeStr("thumbnailBase64"),
                        lastModified    = obj.safeLong("lastModified")
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
        socketClient.addListener("clipboard_sync")  { json ->
            addClipboard(json, "pc")
            // Auto-set Android clipboard when receiving text from PC
            val text = json.safeStr("content") ?: return@addListener
            if (text != "[Image]") {
                try {
                    val cm = getApplication<android.app.Application>()
                        .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("NexLink", text))
                } catch (_: Exception) {}
            }
        }

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

        socketClient.addListener("camera_audio") { json ->
            try {
                if (audioTrack == null) {
                    val sampleRate = 16000
                    val channelConfig = android.media.AudioFormat.CHANNEL_OUT_MONO
                    val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
                    val minBufferSize = android.media.AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                    
                    audioTrack = android.media.AudioTrack.Builder()
                        .setAudioAttributes(android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                        .setAudioFormat(android.media.AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build())
                        .setBufferSizeInBytes(minBufferSize)
                        .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                        .build()
                    audioTrack?.play()
                }

                val b64 = json.safeStr("data") ?: return@addListener
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                audioTrack?.write(bytes, 0, bytes.size)
            } catch (e: Exception) { 
                Log.e(TAG, "Audio play error: ${e.message}")
            }
        }

        socketClient.addListener("file_preview_data") { json ->
            val path = json.safeStr("path", "")
            val data = json.safeStr("data", "")
            _filePreviewData.value = Pair(path, data)
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

        socketClient.addListener("peer_online") { _ ->
            // PC just came online — blast ALL our mobile state to it!
            sendSystemInfoToPC()
            sendAllNotifications()
            viewModelScope.launch {
                kotlinx.coroutines.delay(500)
                sendSmsList()
            }
            viewModelScope.launch {
                kotlinx.coroutines.delay(1000)
                sendPhotoList()
            }
        }

        socketClient.addListener("lock_phone") {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val dpm = getApplication<android.app.Application>().getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    val componentName = android.content.ComponentName(getApplication(), com.phynex.NexLink.service.AdminReceiver::class.java)
                    if (dpm.isAdminActive(componentName)) {
                        dpm.lockNow()
                    } else {
                        // Bring to front first to bypass Android 10+ background restriction
                        val bringToFront = android.content.Intent(getApplication(), com.phynex.NexLink.MainActivity::class.java)
                        bringToFront.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        getApplication<android.app.Application>().startActivity(bringToFront)

                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            val intent = android.content.Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                            intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "NexLink needs Device Admin to remotely lock the screen.")
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            getApplication<android.app.Application>().startActivity(intent)
                            android.widget.Toast.makeText(getApplication(), "Please enable Device Admin to allow screen locking.", android.widget.Toast.LENGTH_LONG).show()
                        }, 500)
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(getApplication(), "Failed to lock screen: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }

        socketClient.addListener("open_camera") { json ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val bringToFront = android.content.Intent(getApplication(), com.phynex.NexLink.MainActivity::class.java)
                    bringToFront.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    getApplication<android.app.Application>().startActivity(bringToFront)

                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            val lens = json.safeStr("lens", "back")
                            val intent = android.content.Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                            if (lens == "front") {
                                intent.putExtra("android.intent.extras.CAMERA_FACING", 1)
                                intent.putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                                intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                            } else {
                                intent.putExtra("android.intent.extras.CAMERA_FACING", 0)
                            }
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            getApplication<android.app.Application>().startActivity(intent)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(getApplication(), "No camera app found", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }, 500)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(getApplication(), "Failed to open camera: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        socketClient.addListener("clear_mobile_notification") { json ->
            val key = json.safeStr("key", "")
            if (key.isNotBlank()) {
                LinkBridgeNotificationService.clearNotification(key)
            }
        }

        socketClient.addListener("request_all_notifications") { _ ->
            sendAllNotifications()
        }

        socketClient.addListener("clear_all_notifications") { _ ->
            LinkBridgeNotificationService.cancelAllNotifications()
        }

        // ── Windows → Android: request SMS list ──────────────────────────
        socketClient.addListener("request_mobile_sms") { _ ->
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { sendSmsList() }
        }

        // ── Windows → Android: request photo list ─────────────────────────
        socketClient.addListener("request_photos") { _ ->
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { sendPhotoList() }
        }

        // ── Windows → Android: request a single photo thumbnail ───────────
        socketClient.addListener("request_photo_thumbnail") { json ->
            val path = json.safeStr("path", "") ?: return@addListener
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                sendPhotoThumbnail(path)
            }
        }

        // ── Windows → Android: set ringer mode ────────────────────────────
        socketClient.addListener("ringer_mode") { json ->
            val mode = json.safeInt("mode", 2) // 0=Silent,1=Vibrate,2=Normal
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val am = getApplication<Application>().getSystemService(
                        android.content.Context.AUDIO_SERVICE) as AudioManager
                    am.ringerMode = mode
                    // Update local state and echo back to Windows
                    _mobileStatus.value = _mobileStatus.value.copy(ringerMode = mode)
                    sendMobileStatus()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set ringer mode: ${e.message}")
                }
            }
        }

        // ── Windows → Android: set phone media volume ─────────────────────
        socketClient.addListener("mobile_volume") { json ->
            val level = json.safeInt("level", 50)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val am = getApplication<Application>().getSystemService(
                        android.content.Context.AUDIO_SERVICE) as AudioManager
                    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val targetVol = (level * maxVol / 100.0).toInt().coerceIn(0, maxVol)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol,
                        AudioManager.FLAG_SHOW_UI)
                    _mobileStatus.value = _mobileStatus.value.copy(phoneVolume = level)
                    sendMobileStatus()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set mobile volume: ${e.message}")
                }
            }
        }

        // ── Windows → Android: set phone ringer volume ─────────────────────
        socketClient.addListener("mobile_ringer_volume") { json ->
            val level = json.safeInt("level", 50)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val am = getApplication<Application>().getSystemService(
                        android.content.Context.AUDIO_SERVICE) as AudioManager
                    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_RING)
                    val targetVol = (level * maxVol / 100.0).toInt().coerceIn(0, maxVol)
                    am.setStreamVolume(AudioManager.STREAM_RING, targetVol,
                        AudioManager.FLAG_SHOW_UI)
                    _mobileStatus.value = _mobileStatus.value.copy(ringerVolume = level)
                    sendMobileStatus()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set mobile ringer volume: ${e.message}")
                }
            }
        }

        // ── Windows → Android: request current mobile status snapshot ─────
        socketClient.addListener("request_info") { _ ->
            sendMobileStatus()
        }

        // ── Windows → Android: send SMS via SmsManager ───────────────────
        socketClient.addListener("sms_send") { json ->
            val threadId = json.safeStr("threadId", "")
            val body     = json.safeStr("body", "")
            if (threadId.isNotBlank() && body.isNotBlank()) {
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    handleSendSms(threadId, body)
                }
            }
        }

        // ── Windows → Android: get full messages for a thread ────────────
        socketClient.addListener("get_thread") { json ->
            val threadId = json.safeStr("threadId", "") ?: return@addListener
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val cr = getApplication<Application>().contentResolver
                    val messages = getMessagesForThread(cr, threadId, 100)
                    socketClient.sendMessage(mapOf(
                        "type"     to "thread_messages",
                        "threadId" to threadId,
                        "messages" to messages
                    ))
                } catch (e: Exception) {
                    Log.e(TAG, "get_thread failed: ${e.message}")
                }
            }
        }

        // ── Windows → Android: Camera Stream ──────────────────────────
        socketClient.addListener("start_mobile_camera") { json ->
            val front = json.safeBool("front", false)
            startMobileCameraStream(front)
        }

        socketClient.addListener("stop_mobile_camera") { _ ->
            stopMobileCameraStream()
        }

        socketClient.addListener("start_mobile_screen") { _ ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                MainActivity.instance?.requestScreenCapture()
            }
        }

        socketClient.addListener("stop_mobile_screen") { _ ->
            stopMobileScreenStream()
        }
        
        socketClient.addListener("webrtc_offer") { json -> json.safeStr("sdp")?.let { webRtcManager.handleOffer(it) } }
        socketClient.addListener("webrtc_answer") { json -> json.safeStr("sdp")?.let { webRtcManager.handleAnswer(it) } }
        socketClient.addListener("webrtc_ice") { json -> json.safeStr("candidate")?.let { webRtcManager.handleIceCandidate(it) } }
    }

    private fun startMobileCameraStream(front: Boolean) {
        if (isCameraStreaming) stopMobileCameraStream()
        isCameraStreaming = true
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(getApplication())
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(480, 640))
                    .build()

                imageAnalysis?.setAnalyzer(cameraExecutor!!) { imageProxy ->
                    processCameraImage(imageProxy)
                }

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(MainActivity.instance!!, selector, imageAnalysis)
            } catch (e: Exception) {
                Log.e(TAG, "CameraX bind failed", e)
                isCameraStreaming = false
            }
        }, ContextCompat.getMainExecutor(getApplication()))
    }

    private fun processCameraImage(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, out)
            val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            
            socketClient.sendMessage(mapOf(
                "type" to "mobile_camera_frame",
                "data" to base64
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Frame process failed", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun stopMobileCameraStream() {
        isCameraStreaming = false
        cameraProvider?.unbindAll()
        cameraExecutor?.shutdown()
        cameraExecutor = null
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
        val imageBytes = out.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        // Rotate if needed
        val matrix = Matrix()
        matrix.postRotate(imageInfo.rotationDegrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun addClipboard(json: JsonObject, source: String) {
        val content = json.safeStr("content") ?: return
        val item    = ClipboardItem(content = content, isImage = false, source = source)
        _clipboardItems.value = (_clipboardItems.value + item).takeLast(50)
    }

    // ─── Forwarding setup ──────────────────────────────────────────────────

    private fun setupNotificationForwarding() {
        LinkBridgeNotificationService.onNotification = { app, title, body, key ->
            val cleanApp = try {
                val pm = getApplication<Application>().packageManager
                val ai = pm.getApplicationInfo(app, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (_: Exception) { app.split(".").last().replaceFirstChar { it.uppercase() } }

            socketClient.sendMessage(mapOf(
                "type"      to "notification",
                "app"       to cleanApp,
                "title"     to title,
                "body"      to body,
                "key"       to key,
                "timestamp" to System.currentTimeMillis()
            ))
            sendMobileStatus()
        }
    }

    fun sendAllNotifications() {
        val notifs = LinkBridgeNotificationService.instance?.getAllActiveNotifications() ?: return
        notifs.forEach { n ->
            val app = n["app"] ?: ""
            val cleanApp = try {
                val pm = getApplication<Application>().packageManager
                val ai = pm.getApplicationInfo(app, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (_: Exception) { app.split(".").last().replaceFirstChar { it.uppercase() } }

            socketClient.sendMessage(mapOf(
                Pair("type",      "notification"),
                Pair("app",       cleanApp),
                Pair("title",     n["title"] ?: ""),
                Pair("body",      n["body"] ?: ""),
                Pair("key",       n["key"] ?: ""),
                Pair("timestamp", System.currentTimeMillis())
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
        sendSystemInfoToPC()
    }

    fun requestSystemInfo() = socketClient.sendMessage(mapOf("type" to "request_info"))

    fun sendMediaControl(action: String) =
        socketClient.sendMessage(mapOf("type" to "media_control", "action" to action))

    fun seekMedia(positionSec: Double) {
        socketClient.sendMessage(mapOf("type" to "media_seek", "position" to positionSec))
        _nowPlaying.value = _nowPlaying.value?.copy(position = positionSec)
    }

    private var lastVolumeSentTime = 0L
    private var lastBrightnessSentTime = 0L

    fun sendVolume(level: Int) {
        _volume.value = level
        val now = System.currentTimeMillis()
        if (now - lastVolumeSentTime > 80) { // Throttle to ~12 updates per second
            _lastSentVolume = level
            socketClient.sendMessage(mapOf("type" to "volume", "level" to level))
            lastVolumeSentTime = now
        }
    }

    fun sendBrightness(level: Int) {
        _brightness.value = level
        val now = System.currentTimeMillis()
        if (now - lastBrightnessSentTime > 80) {
            _lastSentBrightness = level
            socketClient.sendMessage(mapOf("type" to "brightness", "level" to level))
            lastBrightnessSentTime = now
        }
    }

    fun lockPCSimple() = socketClient.sendMessage(mapOf("type" to "lock_pc"))

    fun launchApp(appName: String, appPath: String) =
        socketClient.sendMessage(mapOf("type" to "launch_app", "appName" to appName, "appPath" to appPath))

    fun requestAppList() = socketClient.sendMessage(mapOf("type" to "app_list"))

    fun requestRunningApps() = socketClient.sendMessage(mapOf("type" to "request_info"))

    fun closeApp(appName: String, handle: String) = socketClient.sendMessage(mapOf("type" to "launch_app", "appName" to appName, "appPath" to "##CLOSE##", "appHandle" to handle))

    fun focusApp(appName: String, handle: String) = socketClient.sendMessage(mapOf("type" to "launch_app", "appName" to appName, "appPath" to "##FOCUS##", "appHandle" to handle))

    fun sendSystemInfoToPC() {
        if (!isConnected.value) return
        val context = getApplication<Application>()
        
        Log.d(TAG, "sendSystemInfoToPC started")
        // Battery
        try {
            val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
            val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            socketClient.sendMessage(mapOf("type" to "battery_info", "level" to level, "isCharging" to charging))
            Log.d(TAG, "Sent battery_info: $level% charging=$charging")
        } catch (e: Exception) {
            Log.e(TAG, "Failed battery", e)
        }
        
        // WiFi
        try {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNetwork)
            val wifiOn = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            var ssid = ""
            if (wifiOn) {
                val wm = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                ssid = wm.connectionInfo?.ssid?.replace("\"", "") ?: ""
                if (ssid.isBlank() || ssid == "<unknown ssid>") {
                    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                    val gpsEnabled = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                    ssid = if (!gpsEnabled) "Location Off (Enable for WiFi Name)" else "Connected"
                }
            }
            socketClient.sendMessage(mapOf("type" to "wifi_info", "enabled" to wifiOn, "ssid" to ssid, "connected" to wifiOn))
            Log.d(TAG, "Sent wifi_info: $ssid")
        } catch (e: Exception) {
            Log.e(TAG, "Failed wifi", e)
        }

        // Mobile status (ringer + volume)
        sendMobileStatus()

        // Wallpaper
        try {
            val wm = android.app.WallpaperManager.getInstance(context)
            val drawable = wm.drawable
            if (drawable != null) {
                Log.d(TAG, "Wallpaper drawable acquired, converting to bitmap...")
                val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 800
                val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 450
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                
                val baos = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 20, baos)
                val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                socketClient.sendMessage(mapOf("type" to "mobile_wallpaper", "data" to b64))
                Log.d(TAG, "Sent mobile_wallpaper")
            } else {
                Log.d(TAG, "Wallpaper drawable is null!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get mobile wallpaper", e)
        }
    }

    /** Push current ringer mode + phone volumes to Windows */
    fun sendMobileStatus() {
        if (!isConnected.value) return
        try {
            val context = getApplication<Application>()
            val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
            val ringerMode = am.ringerMode // 0=Silent, 1=Vibrate, 2=Normal

            val maxMedia  = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val curMedia  = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val mediaVolPct = if (maxMedia > 0) (curMedia * 100 / maxMedia) else 0

            val maxRinger  = am.getStreamMaxVolume(AudioManager.STREAM_RING)
            val curRinger  = am.getStreamVolume(AudioManager.STREAM_RING)
            val ringerVolPct = if (maxRinger > 0) (curRinger * 100 / maxRinger) else 0

            val notifCount = LinkBridgeNotificationService.activeCount

            val status = MobileStatus(
                ringerMode   = ringerMode,
                phoneVolume  = mediaVolPct,
                ringerVolume = ringerVolPct,
                notifCount   = notifCount
            )
            _mobileStatus.value = status

            socketClient.sendMessage(mapOf(
                "type"        to "mobile_status",
                "ringerMode"  to ringerMode,
                "phoneVolume" to mediaVolPct,
                "ringerVolume" to ringerVolPct,
                "notifCount"  to notifCount
            ))
            Log.d(TAG, "Sent mobile_status: ringer=$ringerMode vol=$mediaVolPct%")
        } catch (e: Exception) {
            Log.e(TAG, "Failed sendMobileStatus: ${e.message}")
        }
    }

    /**
     * Reads all SMS threads from ContentResolver and sends them to Windows.
     * For each thread, includes the last ~20 messages for conversation view.
     * Looks up contact names from ContactsContract.
     */
    fun sendSmsList() {
        if (!isConnected.value) return
        try {
            val context = getApplication<Application>()
            val cr = context.contentResolver
            val threads = mutableListOf<Map<String, Any>>()

            // Query recent SMS messages directly to find distinct threads (safest cross-OEM method)
            val threadCursor = cr.query(
                Uri.parse("content://sms"),
                arrayOf("thread_id", "address", "body", "date"),
                null, null, "date DESC LIMIT 500"
            )
            val seenThreadIds = mutableSetOf<String>()
            threadCursor?.use { tc ->
                val threadIdCol = tc.getColumnIndex("thread_id")
                val addrCol     = tc.getColumnIndex("address")
                val bodyCol     = tc.getColumnIndex("body")
                val dateCol     = tc.getColumnIndex("date")

                while (tc.moveToNext()) {
                    val threadId = if (threadIdCol >= 0) tc.getString(threadIdCol) ?: "" else ""
                    if (threadId.isBlank() || seenThreadIds.contains(threadId)) continue
                    seenThreadIds.add(threadId)

                    val number   = if (addrCol >= 0) tc.getString(addrCol) ?: "" else ""
                    val snippet  = if (bodyCol >= 0) tc.getString(bodyCol) ?: "" else ""
                    val date     = if (dateCol >= 0) tc.getLong(dateCol) else 0L

                    val contactName = lookupContactName(cr, number) ?: number

                    // Get last 20 messages for this thread
                    val messages = getMessagesForThread(cr, threadId, 20)

                    threads.add(mapOf(
                        "id"            to threadId,
                        "contactName"   to contactName,
                        "contactNumber" to number,
                        "lastMessage"   to snippet,
                        "timestamp"     to date,
                        "unread"        to 0,
                        "messages"      to messages
                    ))
                    if (threads.size >= 50) break // limit to 50 threads
                }
            }

            socketClient.sendMessage(mapOf(
                "type"    to "mobile_sms_list",
                "threads" to threads
            ))
            Log.d(TAG, "Sent mobile_sms_list: ${threads.size} threads")
        } catch (e: Exception) {
            Log.e(TAG, "sendSmsList failed: ${e.message}")
        }
    }

    private fun getNumberForThread(cr: ContentResolver, threadId: String): String {
        return try {
            val cur = cr.query(
                Uri.parse("content://sms"),
                arrayOf("address"),
                "thread_id=?",
                arrayOf(threadId),
                "date DESC"
            )
            cur?.use { if (it.moveToFirst()) it.getString(0) ?: "" else "" } ?: ""
        } catch (_: Exception) { "" }
    }

    private fun lookupContactName(cr: ContentResolver, number: String): String? {
        if (number.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val cur = cr.query(uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            cur?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) { null }
    }

    private fun getMessagesForThread(cr: ContentResolver, threadId: String, limit: Int): List<Map<String, Any>> {
        return try {
            val messages = mutableListOf<Map<String, Any>>()
            val cur = cr.query(
                Uri.parse("content://sms"),
                arrayOf("_id", "body", "date", "type"),
                "thread_id=?",
                arrayOf(threadId),
                "date DESC LIMIT $limit"
            )
            cur?.use { c ->
                val idCol   = c.getColumnIndex("_id")
                val bodyCol = c.getColumnIndex("body")
                val dateCol = c.getColumnIndex("date")
                val typeCol = c.getColumnIndex("type")
                while (c.moveToNext()) {
                    messages.add(mapOf(
                        "id"     to (if (idCol   >= 0) c.getString(idCol)   else ""),
                        "body"   to (if (bodyCol >= 0) c.getString(bodyCol) else ""),
                        "timestamp" to (if (dateCol >= 0) c.getLong(dateCol) else 0L),
                        "isSent" to (if (typeCol >= 0) c.getInt(typeCol) == Telephony.Sms.MESSAGE_TYPE_SENT else false)
                    ))
                }
            }
            messages.reversed() // oldest first for conversation order
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Queries MediaStore for images grouped by album folder.
     * Sends metadata only (no thumbnails). Thumbnails are lazy-loaded on demand.
     * Albums: Camera, WhatsApp Images, Screenshots, Download
     */
    fun sendPhotoList() {
        if (!isConnected.value) return
        try {
            val context = getApplication<Application>()
            val cr = context.contentResolver

            val albumOrder = listOf("Camera", "WhatsApp Images", "Screenshots", "Download")
            val photosByAlbum = mutableMapOf<String, MutableList<Map<String, Any>>>()
            albumOrder.forEach { photosByAlbum[it] = mutableListOf() }

            val projection = arrayOf(
                android.provider.MediaStore.Images.Media._ID,
                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                android.provider.MediaStore.Images.Media.DATE_TAKEN,
                android.provider.MediaStore.Images.Media.DATA,
                android.provider.MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )

            val cur = cr.query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                "${android.provider.MediaStore.Images.Media.DATE_TAKEN} DESC"
            )
            cur?.use { c ->
                val idCol     = c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
                val nameCol   = c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol   = c.getColumnIndex(android.provider.MediaStore.Images.Media.DATE_TAKEN)
                val pathCol   = c.getColumnIndex(android.provider.MediaStore.Images.Media.DATA)
                val bucketCol = c.getColumnIndex(android.provider.MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

                while (c.moveToNext()) {
                    val id     = c.getLong(idCol)
                    val name   = c.getString(nameCol) ?: ""
                    val date   = if (dateCol   >= 0) c.getLong(dateCol)   else 0L
                    val path   = if (pathCol   >= 0) c.getString(pathCol) ?: "" else ""
                    val bucket = if (bucketCol >= 0) c.getString(bucketCol) ?: "" else ""

                    // Map to one of our 4 standard albums
                    val album = when {
                        bucket.contains("Camera", ignoreCase = true)         -> "Camera"
                        bucket.contains("WhatsApp", ignoreCase = true)       -> "WhatsApp Images"
                        bucket.contains("Screenshot", ignoreCase = true)     -> "Screenshots"
                        bucket.contains("Download", ignoreCase = true)       -> "Download"
                        else                                                  -> "Camera" // fallback
                    }

                    val contentUri = ContentUris.withAppendedId(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    photosByAlbum[album]?.add(mapOf(
                        "id"        to id.toString(),
                        "name"      to name,
                        "path"      to contentUri.toString(), // content URI (no real fs path needed)
                        "timestamp" to date,
                        "album"     to album
                        // No thumbnail here — lazy loaded on demand
                    ))
                    // Cap each album at 200 items to avoid huge payloads
                    if ((photosByAlbum[album]?.size ?: 0) >= 200) {
                        // still scan remaining photos for other albums
                    }
                }
            }

            // Build album summary with photo count
            val albums = albumOrder.map { albumName ->
                val photos = photosByAlbum[albumName] ?: emptyList()
                mapOf(
                    "name"       to albumName,
                    "photoCount" to photos.size,
                    "photos"     to photos
                )
            }.filter { (it["photoCount"] as Int) > 0 }

            socketClient.sendMessage(mapOf(
                "type"   to "mobile_photo_list",
                "albums" to albums
            ))
            Log.d(TAG, "Sent mobile_photo_list: ${albums.size} albums")
        } catch (e: Exception) {
            Log.e(TAG, "sendPhotoList failed: ${e.message}")
        }
    }

    /** Lazy-loads a single photo thumbnail and sends it to Windows on demand */
    private fun sendPhotoThumbnail(contentUriStr: String) {
        if (!isConnected.value) return
        try {
            val context = getApplication<Application>()
            val uri = Uri.parse(contentUriStr)
            val bitmap = android.provider.MediaStore.Images.Thumbnails.getThumbnail(
                context.contentResolver,
                ContentUris.parseId(uri),
                android.provider.MediaStore.Images.Thumbnails.MINI_KIND,
                null
            ) ?: return

            val baos = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, baos)
            val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)

            socketClient.sendMessage(mapOf(
                "type"      to "mobile_photo_thumbnail",
                "path"      to contentUriStr,
                "thumbnail" to b64
            ))
        } catch (e: Exception) {
            Log.e(TAG, "sendPhotoThumbnail failed for $contentUriStr: ${e.message}")
        }
    }

    fun browsePath(path: String) {
        _currentPath.value = path
        socketClient.sendMessage(mapOf("type" to "browse", "path" to path))
    }

    fun openFile(path: String) = socketClient.sendMessage(mapOf("type" to "open_file", "path" to path))

    fun requestFilePreview(path: String) {
        _filePreviewData.value = null
        socketClient.sendMessage(mapOf("type" to "file_preview", "path" to path))
    }

    fun clearFilePreview() { _filePreviewData.value = null }

    fun downloadFile(path: String) = socketClient.sendMessage(mapOf("type" to "download_file", "path" to path))

    fun renameFile(path: String, newName: String) {
        socketClient.sendMessage(mapOf("type" to "rename_file", "path" to path, "newName" to newName))
        browsePath(_currentPath.value) // auto refresh
    }

    fun createFolder(path: String, name: String) {
        socketClient.sendMessage(mapOf("type" to "create_folder", "path" to path, "name" to name))
        browsePath(_currentPath.value)
    }

    fun createFile(path: String, name: String) {
        socketClient.sendMessage(mapOf("type" to "create_file", "path" to path, "name" to name))
        browsePath(_currentPath.value)
    }

    fun deleteFile(path: String) {
        socketClient.sendMessage(mapOf("type" to "delete_file", "path" to path))
        browsePath(_currentPath.value)
    }

    fun copyFile(source: String, destDir: String) {
        socketClient.sendMessage(mapOf("type" to "copy_file", "source" to source, "destDir" to destDir))
        browsePath(_currentPath.value)
    }

    fun moveFile(source: String, destDir: String) {
        socketClient.sendMessage(mapOf("type" to "move_file", "source" to source, "destDir" to destDir))
        browsePath(_currentPath.value)
    }

    fun pushClipboard(content: String) {
        _clipboardItems.value = (_clipboardItems.value + ClipboardItem(content = content, source = "phone")).takeLast(50)
        socketClient.sendMessage(mapOf("type" to "clipboard_push", "content" to content))
    }

    fun pushClipboardImage(base64: String) {
        _clipboardItems.value = (_clipboardItems.value + ClipboardItem(content = "[Image]", isImage = true, source = "phone")).takeLast(50)
        socketClient.sendMessage(mapOf("type" to "clipboard_push", "content" to "[Image]", "image" to base64))
    }

    fun pullClipboard() = socketClient.sendMessage(mapOf("type" to "clipboard_pull"))

    fun startScreenStream() {
        _isStreamingCamera.value = false
        _isStreamingScreen.value = true
        socketClient.sendMessage(mapOf("type" to "start_screen"))
    }

    fun startBoundedScreenStream() {
        _isStreamingScreen.value = true
        socketClient.sendMessage(mapOf("type" to "start_screen_bound"))
    }

    fun startExtendScreenStream() {
        _isStreamingScreen.value = true
        socketClient.sendMessage(mapOf("type" to "start_screen_extend"))
    }

    fun stopScreenStream() {
        _isStreamingScreen.value = false
        socketClient.sendMessage(mapOf("type" to "stop_screen"))
        stopMobileScreenStream()
    }

    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null

    fun startMobileScreenStream(resultCode: Int, data: android.content.Intent) {
        val mpManager = getApplication<Application>().getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)
        
        val metrics = getApplication<Application>().resources.displayMetrics
        val width = 720
        val height = 1280
        val dpi = metrics.densityDpi

        val imageReader = android.media.ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay("NexLinkStream", width, height, dpi, android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, null)

        _isStreamingScreen.value = true
        
        viewModelScope.launch {
            while (_isStreamingScreen.value) {
                try {
                    val image = imageReader.acquireLatestImage() ?: continue
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width
                    
                    val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)
                    image.close()

                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
                    val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    
                    socketClient.sendMessage(mapOf(
                        "type" to "mobile_screen_frame",
                        "data" to base64
                    ))
                    delay(100) // 10fps
                } catch (e: Exception) {
                    delay(100)
                }
            }
            imageReader.close()
        }
    }

    fun stopMobileScreenStream() {
        _isStreamingScreen.value = false
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    fun startCameraStream(enableMic: Boolean) {
        if (!isConnected.value) return
        _isStreamingScreen.value = false
        _isStreamingCamera.value = true
        _cameraFrameBase64.value = null
        
        if (enableMic) {
            try {
                val bufferSize = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                audioTrack?.play()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init AudioTrack", e)
            }
        }

        socketClient.sendMessage(mapOf("type" to "start_camera", "enableMic" to enableMic))
    }

    fun stopCameraStream() {
        if (!isConnected.value) return
        _isStreamingCamera.value = false
        socketClient.sendMessage(mapOf("type" to "stop_camera"))
        
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) { }
    }

    fun pinApp(app: AppItem) {
        if (!_pinnedApps.value.any { it.name == app.name }) {
            _pinnedApps.value = _pinnedApps.value + app
        }
    }

    fun clearToast() { _toastMessage.value = null }

    fun sendSms(threadId: String, body: String) {
        // Forward to Android side via socket (the phone sends the SMS using SmsManager)
        socketClient.sendMessage(mapOf("type" to "sms_send", "threadId" to threadId, "body" to body))
    }

    /**
     * Called when Android receives `sms_send` from Windows.
     * Sends the SMS using SmsManager and then refreshes the thread.
     */
    private fun handleSendSms(threadId: String, body: String) {
        try {
            val context = getApplication<Application>()
            val cr = context.contentResolver
            val number = getNumberForThread(cr, threadId)
            if (number.isBlank()) {
                Log.e(TAG, "handleSendSms: no number for thread $threadId")
                return
            }
            @Suppress("DEPRECATION")
            val smsManager: SmsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(body)
            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            Log.d(TAG, "SMS sent to $number: $body")
            // Refresh thread list after short delay
            viewModelScope.launch {
                kotlinx.coroutines.delay(1000)
                sendSmsList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleSendSms failed: ${e.message}")
            _toastMessage.value = "Failed to send SMS: ${e.message}"
        }
    }

    // ─── USB / touchpad events ─────────────────────────────────────────────

    fun lockPC() {
        if (!isConnected.value) return
        socketClient.sendMessage(mapOf("type" to "lock_pc"))
    }

    fun powerCommand(action: String) {
        if (!isConnected.value) return
        socketClient.sendRaw("power_command", org.json.JSONObject().apply { put("action", action) })
    }

    fun onUsbConnected() {
        _isUsbMode.value = true
        socketClient.sendMessage(mapOf("type" to "usb_connected"))
    }

    fun onUsbDisconnected() {
        _isUsbMode.value = false
        socketClient.sendMessage(mapOf("type" to "usb_disconnected"))
    }

    private var lastMouseTime = 0L
    private var accDx = 0f
    private var accDy = 0f
    private var mouseJob: kotlinx.coroutines.Job? = null

    fun sendMouseMove(dx: Float, dy: Float) {
        if (!isConnected.value) return
        accDx += dx
        accDy += dy
        
        val now = System.currentTimeMillis()
        if (now - lastMouseTime > 15) {
            flushMouseMove()
        } else if (mouseJob == null || mouseJob?.isActive == false) {
            mouseJob = viewModelScope.launch {
                kotlinx.coroutines.delay(15)
                flushMouseMove()
            }
        }
    }

    private fun flushMouseMove() {
        if (accDx == 0f && accDy == 0f) return
        socketClient.sendRaw("mouse_move", org.json.JSONObject().apply {
            put("dx", accDx.toDouble())
            put("dy", accDy.toDouble())
        })
        accDx = 0f
        accDy = 0f
        lastMouseTime = System.currentTimeMillis()
    }

    fun sendMouseTap() {
        socketClient.sendMessage(mapOf("type" to "mouse_tap"))
    }

    fun sendMouseRightTap() {
        socketClient.sendMessage(mapOf("type" to "mouse_right_tap"))
    }

    fun sendMouseMiddleTap() {
        socketClient.sendMessage(mapOf("type" to "mouse_middle_tap"))
    }

    fun sendMouseScroll(dy: Float) {
        socketClient.sendMessage(mapOf("type" to "mouse_scroll", "dy" to dy))
    }

    fun sendMouseHScroll(dx: Float) {
        socketClient.sendMessage(mapOf("type" to "mouse_scroll", "dx" to dx, "dy" to 0f, "horizontal" to true))
    }

    fun sendKeyPress(keyCode: String) {
        if (!isConnected.value) return
        socketClient.sendMessage(mapOf("type" to "key_press", "key" to keyCode))
    }
}
