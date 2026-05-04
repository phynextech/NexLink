package com.phynex.NexLink.model

data class DeviceInfo(
    val userId: String,
    val deviceId: String,
    val deviceName: String,
    val pairId: String,
    val relayUrl: String = "https://nexlink-khhe.onrender.com"
)

data class WifiInfo(
    val ssid: String,
    val strength: Int,
    val connected: Boolean = false
)

data class BatteryInfo(
    val level: Int,
    val isCharging: Boolean
)

data class BluetoothDevice(
    val name: String,
    val address: String,
    val type: String
)

data class NowPlaying(
    val title: String,
    val artist: String,
    val albumArtBase64: String?,
    val isPlaying: Boolean,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val appSource: String = "",      // e.g. "Spotify", "YouTube (Brave)"
    val shuffleActive: Boolean = false,
    val repeatMode: Int = 0           // 0=Off, 1=All, 2=One
)

data class AppItem(
    val name: String,
    val path: String,
    val iconBase64: String? = null,
    val category: String = "",
    val handle: String = "",
    val isForeground: Boolean = false
)

data class PerformanceMetrics(
    val cpu: Int = -1,
    val gpu: Int = -1,
    val ram: Int = -1,
    val vram: Int = -1,
    val fps: Int = -1,
    val wifi: Int = -1
)

data class FileItem(
    val name: String,
    val path: String,
    val size: Long,
    val isDirectory: Boolean,
    val type: String,
    val thumbnailBase64: String? = null,
    val lastModified: Long = 0L
)

data class ClipboardItem(
    val content: String,
    val isImage: Boolean = false,
    val source: String = "pc",   // "pc" or "phone"
    val timestamp: Long = System.currentTimeMillis()
)

data class SmsThread(
    val id: String,
    val contactName: String,
    val contactNumber: String,
    val lastMessage: String,
    val timestamp: Long,
    val unread: Int,
    val messages: List<SmsMessage> = emptyList()
)

data class SmsMessage(
    val id: String,
    val body: String,
    val timestamp: Long,
    val isSent: Boolean
)

data class NotificationItem(
    val app: String,
    val title: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class PhotoItem(
    val name: String,
    val path: String,
    val thumbnailBase64: String?,
    val timestamp: Long,
    val album: String = "Camera"   // "Camera", "WhatsApp Images", "Screenshots", "Download"
)

/** Groups photos by album folder for display in Windows Photos tab */
data class PhotoAlbum(
    val name: String,               // "Camera", "WhatsApp Images", "Screenshots", "Download"
    val coverThumbnail: String?,    // base64 of first photo thumbnail
    val photoCount: Int,
    val photos: List<PhotoItem> = emptyList()
)

/**
 * Live Android device status pushed to Windows in real-time.
 * ringerMode: 0=Silent, 1=Vibrate, 2=Normal
 */
data class MobileStatus(
    val ringerMode: Int = 2,        // AudioManager.RINGER_MODE_NORMAL
    val phoneVolume: Int = 50,      // 0-100 (media stream percentage)
    val ringerVolume: Int = 50,     // 0-100 (ringer stream percentage)
    val notifCount: Int = 0,        // active notification count
    val isDoNotDisturb: Boolean = false
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

enum class Screen(val route: String) {
    SPLASH("splash"),
    QR_SCANNER("qr_scanner"),
    HOME("home"),
    MUSIC_CONTROL("music_control"),
    APP_LAUNCHER("app_launcher"),
    FILE_BROWSER("file_browser"),
    CLIPBOARD("clipboard"),
    CAMERA_SCREEN("camera_screen"),
    TRACKPAD("trackpad")
}
