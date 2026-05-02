package com.phynex.NexLink.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.phynex.NexLink.MainActivity
import com.phynex.NexLink.viewmodel.MainViewModel
import kotlinx.coroutines.*

/**
 * Keeps the WebSocket alive even when the app is removed from recent apps.
 * Also monitors WiFi changes and triggers reconnect automatically.
 */
class NexLinkConnectionService : Service() {

    companion object {
        const val TAG = "NexLinkSvc"
        const val CHANNEL_ID = "nexlink_connection"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.phynex.NexLink.STOP"

        fun start(context: Context) {
            val intent = Intent(context, NexLinkConnectionService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, NexLinkConnectionService::class.java)
            intent.action = ACTION_STOP
            context.startService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification("Connecting..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
        }

        // Acquire partial wake lock to keep CPU running
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NexLink::ConnectionLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 hours max

        registerNetworkCallback()
        Log.d(TAG, "NexLink Connection Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY // Restart if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        unregisterNetworkCallback()
        wakeLock?.release()
        Log.d(TAG, "NexLink Connection Service destroyed")
    }

    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available - triggering reconnect")
                updateNotification("Connected to network")
                // Give network a moment to fully initialize
                scope.launch {
                    delay(2000)
                    MainViewModel.instance?.reconnectIfNeeded()
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                updateNotification("Waiting for WiFi...")
            }
        }

        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try { connectivityManager?.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
    }

    fun updateNotification(status: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, NexLinkConnectionService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NexLink")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_delete, "Disconnect", stopPi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NexLink Connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps NexLink connected to your PC"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
