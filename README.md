# NexLink

**NexLink** connects your Android phone and Windows PC over local WiFi (LAN) using WebSockets. Scan a QR code and instantly control your PC, sync clipboard, stream screen/camera, manage files, forward notifications, and more — all from your phone.

---

## 📁 Project Structure

```
E:/CR/
├── Android/          ← Kotlin/Jetpack Compose Android app
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/phynex/NexLink/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── model/Models.kt
│   │   │   │   ├── websocket/NexLinkWebSocket.kt
│   │   │   │   ├── service/NexLinkNotificationService.kt
│   │   │   │   ├── service/SmsReceiver.kt
│   │   │   │   ├── viewmodel/MainViewModel.kt
│   │   │   │   └── ui/
│   │   │   │       ├── theme/ (Color, Theme, Typography)
│   │   │   │       └── screens/ (QRScanner, Home, Music, AppLauncher,
│   │   │   │                      FileBrowser, Clipboard, CameraScreen)
│   │   │   ├── AndroidManifest.xml
│   │   │   └── res/values/themes.xml
│   │   └── build.gradle.kts
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── gradle/libs.versions.toml
│
└── Windows/          ← C# WPF .NET 8 Windows app
    └── NexLink/
        ├── App.xaml / App.xaml.cs
        ├── MainWindow.xaml / MainWindow.xaml.cs
        ├── NexLink.csproj
        ├── Models/DataModels.cs
        ├── Services/
        │   ├── WebSocketService.cs
        │   ├── SystemInfoService.cs
        │   ├── ScreenCaptureService.cs
        │   ├── CameraService.cs
        │   ├── MediaControlService.cs
        │   ├── ClipboardService.cs
        │   └── QRCodeService.cs
        ├── ViewModels/MainViewModel.cs
        └── Helpers/Converters.cs
```

---

## 🚀 Getting Started

### Prerequisites

| Tool | Version |
|------|---------|
| Android Studio | Hedgehog 2023.1+ |
| JDK | 17+ |
| Android SDK | API 26+ |
| .NET SDK | 8.0+ |
| Visual Studio 2022 | with WPF workload |
| Windows | 10 / 11 (64-bit) |

---

## 📱 Building the Android App

### Step 1 — Open the project
```
Open Android Studio → File → Open → E:/CR/Android
```

### Step 2 — Sync Gradle
Android Studio will automatically sync. If not, click **"Sync Project with Gradle Files"**.

### Step 3 — Grant required permissions (first run)
On the device, grant all permissions when prompted:
- Camera (for QR scanning)
- Notification Access (Settings → Notification Listener → enable NexLink)
- SMS (for SMS forwarding)
- Storage (for file browsing)

### Step 4 — Build & Run
```
Run → Run 'app'   (or press Shift+F10)
```
The app targets **Android 8.0+ (API 26)**.

### Required Permissions Summary (AndroidManifest.xml)
| Permission | Purpose |
|---|---|
| `CAMERA` | QR scanner |
| `INTERNET` | WebSocket communication |
| `ACCESS_WIFI_STATE` | WiFi info |
| `READ_MEDIA_IMAGES/VIDEO/AUDIO` | File browsing |
| `RECEIVE_SMS / READ_SMS` | SMS forwarding |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Notification forwarding |
| `POST_NOTIFICATIONS` | Local notifications |
| `FOREGROUND_SERVICE` | Background connectivity |

---

## 🖥️ Building the Windows App

### Step 1 — Open solution
```
Open Visual Studio 2022 → Open → E:/CR/Windows/NexLink/NexLink.csproj
```

### Step 2 — Restore NuGet packages
```
Tools → NuGet Package Manager → Package Manager Console
PM> dotnet restore
```

Or right-click solution → **Restore NuGet Packages**.

### Step 3 — Build & Run
```
Debug → Start Debugging   (F5)
```

### NuGet Packages Required
| Package | Version | Purpose |
|---|---|---|
| `WebSocketSharp-netstandard` | 1.0.1 | WebSocket server |
| `QRCoder` | 1.6.0 | QR code generation |
| `System.Drawing.Common` | 8.0.0 | Screen capture |
| `Newtonsoft.Json` | 13.0.3 | JSON serialization |
| `OpenCvSharp4` | 4.9.0 | Webcam streaming |
| `OpenCvSharp4.runtime.win` | 4.9.0 | OpenCV Windows runtime |
| `NAudio` | 2.2.1 | System volume control |

---

## 🔗 Connection Flow

```
Windows PC (NexLink.exe)
  ↓  Generates QR code containing: { ip, port, deviceName, sessionToken }
  ↓  Starts WebSocket server on port 8765
  
Android Phone (NexLink App)
  ↓  Scans QR code with camera
  ↓  Connects via WebSocket to ws://<ip>:8765
  ↓  Navigates to Home Screen on success
  
Both devices are now linked! ✓
```

**Both devices must be on the same WiFi network.**

---

## 📡 WebSocket Message Protocol

All messages are JSON strings: `{ "type": "...", ...payload }`

| Type | Direction | Description |
|---|---|---|
| `handshake` | → PC | Initial connection identification |
| `request_info` | → PC | Request system info |
| `wifi_info` | PC → | WiFi SSID and strength |
| `battery_info` | PC → | Battery level and charging state |
| `bt_info` | PC → | Connected Bluetooth devices |
| `wallpaper` | PC → | Desktop wallpaper as base64 |
| `now_playing` | PC → | Currently playing media info |
| `media_control` | → PC | Play/pause/prev/next |
| `volume` | ↔ | Volume level 0–100 |
| `brightness` | → PC | Brightness level 0–100 |
| `lock_pc` | → PC | Lock the workstation |
| `app_list` | PC → | List of installed apps |
| `launch_app` | → PC | Open an app on PC |
| `browse` | → PC | Request folder contents |
| `file_list` | PC → | List of files/folders |
| `open_file` | → PC | Open file on PC |
| `download_file` | → PC | Start file download |
| `file_chunk` | PC → | Binary file data chunk |
| `start_screen` | → PC | Begin screen capture stream |
| `stop_screen` | → PC | Stop screen capture |
| `screen_frame` | PC → | JPEG frame as base64 |
| `start_camera` | → PC | Begin webcam stream |
| `stop_camera` | → PC | Stop webcam |
| `camera_frame` | PC → | MJPEG frame as base64 |
| `clipboard_push` | ↔ | Push text to remote clipboard |
| `clipboard_pull` | ↔ | Request remote clipboard content |
| `notification` | → PC | Android notification forwarded |
| `sms_list` | PC → | List of SMS threads |
| `sms_send` | → PC | Send SMS reply |
| `get_thread` | → PC | Request conversation messages |
| `photo_list` | PC → | Recent photos |

---

## ⚠️ Troubleshooting

### Android can't connect
- Make sure **both devices are on the same WiFi** (not mobile data)
- Check Windows Firewall allows port `8765` (TCP inbound)
  ```
  netsh advfirewall firewall add rule name="NexLink" dir=in action=allow protocol=TCP localport=8765
  ```
- Disable VPN if active

### QR code doesn't scan
- Ensure camera permission is granted
- Move the phone closer (10–30 cm from screen)
- Make sure the PC display is bright enough

### Notification listener not working
- Go to **Android Settings → Apps → Special App Access → Notification Access**
- Enable **NexLink**
- Restart the app

### Screen streaming is slow
- Screen frames are capped at **1 per 500ms** (2 FPS) to reduce bandwidth
- Adjust `intervalMs` in `ScreenCaptureService.StartStreaming()` for faster/slower rates

### OpenCV camera error
- Install **Visual C++ Redistributable 2015–2022 x64**
- Ensure a USB or built-in webcam is present
- If no webcam, the camera feature shows a friendly error

---

## 🎨 UI Design

| Property | Value |
|---|---|
| Theme | Dark |
| Accent Color | `#378ADD` (Blue) |
| Background | `#0D1117` |
| Card surface | `#161B22` |
| Font (Android) | System default (Compose) |
| Font (Windows) | Segoe UI |

---

## 📝 License

MIT — free for personal and commercial use.
