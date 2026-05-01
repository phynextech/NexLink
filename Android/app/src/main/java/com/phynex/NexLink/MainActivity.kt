package com.phynex.NexLink

import android.os.Bundle
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start persistent background service on app launch
        NexLinkConnectionService.start(this)

        setContent {
            LinkBridgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = background
                ) {
                    LinkBridgeApp()
                }
            }
        }
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
            slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(300))
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { -it / 3 },
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(200))
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -it / 3 },
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(300))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(200))
        }
    ) {
        composable(Screen.SPLASH.route) {
            SplashScreen(onSplashFinished = {
                // If we already have a saved pairId, go straight to Home
                // The MainViewModel's init block handles the actual connection.
                if (viewModel.isConnected.value || viewModel.connectedDevice.value != null) {
                    navController.navigate(Screen.HOME.route) {
                        popUpTo(Screen.SPLASH.route) { inclusive = true }
                    }
                } else {
                    navController.navigate(Screen.QR_SCANNER.route) {
                        popUpTo(Screen.SPLASH.route) { inclusive = true }
                    }
                }
            })
        }

        composable(Screen.QR_SCANNER.route) {
            QRScannerScreen(
                onScanned = { deviceInfo ->
                    viewModel.connectToPC(deviceInfo)
                    navController.navigate(Screen.HOME.route) {
                        popUpTo(Screen.QR_SCANNER.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.HOME.route) {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToMusic = { navController.navigate(Screen.MUSIC_CONTROL.route) },
                onNavigateToAppLauncher = { navController.navigate(Screen.APP_LAUNCHER.route) },
                onNavigateToClipboard = { navController.navigate(Screen.CLIPBOARD.route) },
                onNavigateToCameraScreen = { navController.navigate(Screen.CAMERA_SCREEN.route) },
                onNavigateToFileBrowser = { navController.navigate(Screen.FILE_BROWSER.route) }
            )
        }

        composable(Screen.MUSIC_CONTROL.route) {
            MusicControlScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.APP_LAUNCHER.route) {
            AppLauncherScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenFileBrowser = { navController.navigate(Screen.FILE_BROWSER.route) }
            )
        }

        composable(Screen.FILE_BROWSER.route) {
            FileBrowserScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.CLIPBOARD.route) {
            ClipboardScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.CAMERA_SCREEN.route) {
            CameraScreenPage(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
