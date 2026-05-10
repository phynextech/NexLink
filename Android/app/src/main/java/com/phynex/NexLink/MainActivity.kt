package com.phynex.NexLink

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.phynex.NexLink.model.DeviceInfo
import com.phynex.NexLink.model.Screen
import com.phynex.NexLink.service.NexLinkConnectionService
import com.phynex.NexLink.ui.screens.*
import com.phynex.NexLink.ui.theme.background
import com.phynex.NexLink.ui.theme.LinkBridgeTheme
import com.phynex.NexLink.viewmodel.MainViewModel
import com.phynex.NexLink.viewmodel.ChatViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        var instance: MainActivity? = null
    }

    private var clipboardManager: ClipboardManager? = null
    private var lastClipHash: Int = 0
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        val vm = MainViewModel.instance ?: return@OnPrimaryClipChangedListener
        if (!vm.isConnected.value) return@OnPrimaryClipChangedListener
        try {
            val item = clipboardManager?.primaryClip?.getItemAt(0)
            val text = item?.text?.toString()
            if (!text.isNullOrEmpty()) {
                val hash = text.hashCode()
                if (hash != lastClipHash) {
                    lastClipHash = hash
                    vm.pushClipboard(text)
                }
            } else if (item?.uri != null) {
                val hash = item.uri.hashCode()
                if (hash != lastClipHash) {
                    lastClipHash = hash
                    vm.viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(contentResolver, item.uri))
                            } else {
                                @Suppress("DEPRECATION")
                                android.provider.MediaStore.Images.Media.getBitmap(contentResolver, item.uri)
                            }
                            val out = java.io.ByteArrayOutputStream()
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
                            val base64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
                            vm.pushClipboardImage(base64)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    val isPiPMode = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun enterPiP() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPiPMode.value = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        enableEdgeToEdge()

        // Start persistent background service
        NexLinkConnectionService.start(this)

        // Request standard permissions
        val permissionsToRequest = mutableListOf<String>()
        val requiredPermissions = mutableListOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            requiredPermissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        requiredPermissions.forEach { perm ->
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, perm)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(perm)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, permissionsToRequest.toTypedArray(), 100)
        }

        // Request MANAGE_EXTERNAL_STORAGE for full file system / wallpaper access (Android 11+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = android.net.Uri.parse("package:$packageName")
                    startActivity(intent)
                    android.widget.Toast.makeText(this, "Please grant All Files Access to sync your wallpaper.", android.widget.Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }

        // Notification Access
        val nlsEnabled = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (nlsEnabled == null || !nlsEnabled.contains(packageName)) {
            android.widget.Toast.makeText(this, "Please enable Notification Access for NexLink to sync notifications", android.widget.Toast.LENGTH_LONG).show()
            startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // Device Admin
        val dpm = getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val adminComponent = android.content.ComponentName(this, com.phynex.NexLink.service.AdminReceiver::class.java)
        if (!dpm.isAdminActive(adminComponent)) {
            val intent = android.content.Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Please enable Device Admin to allow screen locking from Windows.")
            startActivity(intent)
        }

        // Register clipboard listener
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)

        setContent {
            val viewModel: MainViewModel = viewModel()
            val chatVm: ChatViewModel    = viewModel()
            val themeMode by viewModel.themeMode.collectAsState()
            val primaryColor by viewModel.primaryColor.collectAsState()

            // Wire ChatViewModel send function to socket client
            LaunchedEffect(Unit) {
                chatVm.socketSend = { event, data -> viewModel.socketClient.sendRaw(event, data) }

                val CHAT_EVENTS = listOf(
                    "chat_message",
                    "chat_file_offer", "chat_file_accept", "chat_file_reject",
                    "chat_file_chunk", "chat_file_ack", "chat_file_done",
                    "chat_file_pause", "chat_file_resume", "chat_file_cancel",
                    "chat_typing", "chat_delivered", "chat_read",
                    "chat_reaction", "chat_history", "chat_clipboard",
                    "chat_screenshot", "chat_star",
                    "peer_online", "peer_offline",
                )
                CHAT_EVENTS.forEach { event ->
                    viewModel.socketClient.addListener(event) { data ->
                        chatVm.handleEvent(event, data)
                        // When PC comes online, immediately request history
                        if (event == "peer_online") {
                            chatVm.requestHistory()
                        }
                    }
                }

                // Set roomKey for local persistence (userId:deviceId format)
                viewModel.socketClient.isConnected.collect { connected ->
                    if (connected) {
                        val device = viewModel.connectedDevice.value ?: return@collect
                        chatVm.setRoomKey("${device.userId}:${device.deviceId}")
                    }
                }
            }

            LinkBridgeTheme(themeMode = themeMode, primaryColorName = primaryColor) {
                Surface(modifier = Modifier.fillMaxSize(), color = background) {
                    LinkBridgeApp(viewModel, chatVm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        instance = this
        // On Android 10+, background clipboard listening is blocked.
        // Sync the clipboard immediately when the app returns to the foreground.
        val vm = MainViewModel.instance ?: return
        if (vm.isConnected.value && clipboardManager?.hasPrimaryClip() == true) {
            try {
                val item = clipboardManager?.primaryClip?.getItemAt(0)
                val text = item?.text?.toString()
                if (!text.isNullOrEmpty()) {
                    val hash = text.hashCode()
                    if (hash != lastClipHash) {
                        lastClipHash = hash
                        vm.pushClipboard(text)
                    }
                }
            } catch (_: Exception) {}
        }
        // Push live mobile status (ringer/volume) every time we return to foreground
        if (vm.isConnected.value) {
            vm.sendMobileStatus()
        }
    }

    // ── Volume hardware key interception ────────────────────────────────────
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val vm = MainViewModel.instance
        if (vm != null && vm.isConnected.value) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    val newVol = (vm.volume.value + 5).coerceAtMost(100)
                    vm.sendVolume(newVol)
                    return true // consume — suppress phone volume change
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    val newVol = (vm.volume.value - 5).coerceAtLeast(0)
                    vm.sendVolume(newVol)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private val screenCaptureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            MainViewModel.instance?.startMobileScreenStream(result.resultCode, result.data!!)
        }
    }

    fun requestScreenCapture() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        instance = null
    }
}

@Composable
fun LinkBridgeApp(viewModel: MainViewModel, chatVm: ChatViewModel) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.SPLASH.route,
        enterTransition = {
            slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300, easing = FastOutSlowInEasing)) + fadeIn(tween(300))
        },
        exitTransition = {
            slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween(300, easing = FastOutSlowInEasing)) + fadeOut(tween(200))
        },
        popEnterTransition = {
            slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = tween(300, easing = FastOutSlowInEasing)) + fadeIn(tween(300))
        },
        popExitTransition = {
            slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300, easing = FastOutSlowInEasing)) + fadeOut(tween(200))
        }
    ) {
        composable(Screen.SPLASH.route) {
            SplashScreen(onSplashFinished = {
                if (viewModel.isConnected.value || viewModel.connectedDevice.value != null) {
                    navController.navigate(Screen.HOME.route) { popUpTo(Screen.SPLASH.route) { inclusive = true } }
                } else {
                    navController.navigate(Screen.QR_SCANNER.route) { popUpTo(Screen.SPLASH.route) { inclusive = true } }
                }
            })
        }

        composable(Screen.QR_SCANNER.route) {
            QRScannerScreen(onScanned = { deviceInfo ->
                viewModel.connectToPC(deviceInfo)
                navController.navigate(Screen.HOME.route) { popUpTo(Screen.QR_SCANNER.route) { inclusive = true } }
            })
        }

        composable(Screen.HOME.route) {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToMusic        = { navController.navigate(Screen.MUSIC_CONTROL.route) },
                onNavigateToAppLauncher  = { navController.navigate(Screen.APP_LAUNCHER.route) },
                onNavigateToClipboard    = { navController.navigate(Screen.CLIPBOARD.route) },
                onNavigateToCameraScreen = { navController.navigate(Screen.CAMERA_SCREEN.route) },
                onNavigateToExtendScreen = { navController.navigate(Screen.EXTEND_SCREEN.route) },
                onNavigateToFileBrowser  = { navController.navigate(Screen.FILE_BROWSER.route) },
                onNavigateToTrackpad     = { navController.navigate(Screen.TRACKPAD.route) },
                onNavigateToSettings     = { navController.navigate(Screen.SETTINGS.route) },
                onNavigateToChat         = { navController.navigate(Screen.CHAT.route) },
            )
        }

        composable(Screen.MUSIC_CONTROL.route) {
            MusicControlScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }

        composable(Screen.APP_LAUNCHER.route) {
            AppLauncherScreen(viewModel = viewModel, onBack = { navController.popBackStack() }, onOpenFileBrowser = { navController.navigate(Screen.FILE_BROWSER.route) })
        }

        composable(Screen.FILE_BROWSER.route) {
            FileBrowserScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }

        composable(Screen.CLIPBOARD.route) {
            ClipboardScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }

        composable(Screen.CAMERA_SCREEN.route) {
            CameraScreenPage(viewModel = viewModel, onBack = { navController.popBackStack() })
        }

        composable(Screen.EXTEND_SCREEN.route) {
            ExtendScreenPage(viewModel = viewModel, onBack = { navController.popBackStack() })
        }

        composable(Screen.TRACKPAD.route) {
            TrackpadScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }

        composable(Screen.SETTINGS.route) {
            SettingsScreen(
                viewModel = viewModel, 
                onBack = { navController.popBackStack() },
                onNavigateToQrScanner = { navController.navigate(Screen.QR_SCANNER.route) }
            )
        }

        composable(Screen.CHAT.route) {
            ChatScreen(
                chatVm = chatVm,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
