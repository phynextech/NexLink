package com.phynex.NexLink.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class LinkBridgeNotificationService : NotificationListenerService() {
    companion object {
        var onNotification: ((app: String, title: String, body: String, key: String) -> Unit)? = null
        var instance: LinkBridgeNotificationService? = null
        private val TAG = "LinkBridgeNotifSvc"

        @Volatile var activeCount: Int = 0

        fun clearNotification(key: String) {
            try {
                instance?.cancelNotification(key)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear notification: $key", e)
            }
        }

        fun cancelAllNotifications() {
            try {
                instance?.cancelAllNotifications()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear all notifications", e)
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        activeCount = try { activeNotifications?.size ?: 0 } catch (_: Exception) { 0 }
        Log.d(TAG, "Notification listener connected")
    }

    /**
     * Called by the system when the binder to this service is lost (e.g. process death/restart).
     * Clearing [instance] prevents callers from holding a dead binder reference, which is what
     * was causing the cascade of IllegalArgumentException + DeadObjectException in system_server
     * logs during app restarts. The system automatically rebinds us once the new process is ready.
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
        Log.d(TAG, "Notification listener disconnected — instance cleared")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        try {
            val extras = sbn.notification.extras
            val app = sbn.packageName ?: "Unknown"
            val title = extras?.getCharSequence("android.title")?.toString() ?: ""
            val body = extras?.getCharSequence("android.text")?.toString() ?: ""
            val key = sbn.key ?: ""

            if (title.isBlank() && body.isBlank()) return
            if (app.contains("com.phynex.NexLink")) return

            activeCount = try { activeNotifications?.size ?: activeCount } catch (_: Exception) { activeCount }
            Log.d(TAG, "Notification: app=$app title=$title key=$key")
            onNotification?.invoke(app, title, body, key)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        activeCount = try {
            activeNotifications?.size ?: (activeCount - 1).coerceAtLeast(0)
        } catch (_: Exception) {
            (activeCount - 1).coerceAtLeast(0)
        }
    }

    fun getAllActiveNotifications(): List<Map<String, String>> {
        return try {
            activeNotifications?.map { sbn ->
                val extras = sbn.notification.extras
                mapOf(
                    "app" to (sbn.packageName ?: "Unknown"),
                    "title" to (extras?.getCharSequence("android.title")?.toString() ?: ""),
                    "body" to (extras?.getCharSequence("android.text")?.toString() ?: ""),
                    "key" to (sbn.key ?: "")
                )
            }?.filter { it["title"]!!.isNotBlank() || it["body"]!!.isNotBlank() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
