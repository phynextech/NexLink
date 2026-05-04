package com.phynex.NexLink.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class LinkBridgeNotificationService : NotificationListenerService() {
    companion object {
        var onNotification: ((app: String, title: String, body: String) -> Unit)? = null
        private val TAG = "LinkBridgeNotifSvc"

        /** Live count of active (non-dismissed) notifications — read by MainViewModel */
        @Volatile var activeCount: Int = 0
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        try {
            val extras = sbn.notification.extras
            val app = sbn.packageName ?: "Unknown"
            val title = extras?.getCharSequence("android.title")?.toString() ?: ""
            val body = extras?.getCharSequence("android.text")?.toString() ?: ""

            if (title.isBlank() && body.isBlank()) return
            if (app.contains("com.phynex.NexLink")) return  // skip own notifications

            // Update live active count
            try { activeCount = activeNotifications?.size ?: activeCount } catch (_: Exception) { activeCount++ }

            Log.d(TAG, "Notification: app=$app title=$title (active=$activeCount)")
            onNotification?.invoke(app, title, body)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        try {
            activeCount = activeNotifications?.size ?: (activeCount - 1).coerceAtLeast(0)
        } catch (_: Exception) {
            activeCount = (activeCount - 1).coerceAtLeast(0)
        }
    }
}
