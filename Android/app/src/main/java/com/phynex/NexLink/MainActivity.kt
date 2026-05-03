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

class MainActivity : ComponentActivity() {

    private var clipboardManager: ClipboardManager? = null
    private var lastClipHash: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start persistent background service
        NexLinkConnectionService.start(this)

        // Register clipboard listener
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener {
            val vm = MainViewModel.instance ?: return@addPrimaryClipChangedListener
            if (!vm.isConnected.value) return@addPrimaryClipChangedListener
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

        setContent {
            LinkBridgeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = background) {
                    LinkBridgeApp()
                }
            }
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

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager?.removePrimaryClipChangedListener {}
    }
}

@Composable
fun LinkBridgeApp() {
    val navController = rememberNavController()
    val viewModel: MainViewModel = viewModel()

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
                onNavigateToFileBrowser  = { navController.navigate(Screen.FILE_BROWSER.route) }
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
    }
}
