package com.phynex.NexLink.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class LinkBridgeNotificationService : NotificationListenerService() {
    companion object {
        var onNotification: ((app: String, title: String, body: String) -> Unit)? = null
        private val TAG = "LinkBridgeNotifSvc"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        try {
            val extras = sbn.notification.extras
            val app = sbn.packageName ?: "Unknown"
            val title = extras?.getCharSequence("android.title")?.toString() ?: ""
            val body = extras?.getCharSequence("android.text")?.toString() ?: ""

            if (title.isBlank() && body.isBlank()) return
            if (app.contains("com.linkbridge")) return  // skip own notifications

            Log.d(TAG, "Notification: app=$app title=$title")
            onNotification?.invoke(app, title, body)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Optional: handle removal
    }
}
