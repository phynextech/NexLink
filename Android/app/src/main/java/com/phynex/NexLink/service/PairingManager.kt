package com.phynex.NexLink.service




import android.content.Context
import android.content.SharedPreferences

/**
 * Manages the persistent device pairing (cloud-only architecture).
 *
 * Stores:
 *   - userId      — Firebase Auth UID
 *   - deviceId    — UUID identifying this phone
 *   - pairId      — server-issued pairing token
 *   - relayUrl    — Render server URL (wss://...)
 *   - idToken     — Firebase ID token for reconnects (refreshed by auth)
 *
 * No IP address or LAN port is stored — all connections go through Render.
 */
class PairingManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("NexLinkPairing", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_USER_ID   = "saved_user_id"
        private const val KEY_DEVICE_ID = "saved_device_id"
        private const val KEY_PAIR_ID   = "saved_pair_id"
        private const val KEY_RELAY_URL = "saved_relay_url"
        private const val KEY_TOKEN     = "saved_id_token"
        private const val KEY_DEV_NAME  = "saved_device_name"

        const val DEFAULT_RELAY_URL = "https://nexlink-khhe.onrender.com"
    }

    fun savePairing(
        userId: String,
        deviceId: String,
        pairId: String,
        relayUrl: String = DEFAULT_RELAY_URL,
        idToken: String = "",
        deviceName: String = ""
    ) {
        prefs.edit()
            .putString(KEY_USER_ID,   userId)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_PAIR_ID,   pairId)
            .putString(KEY_RELAY_URL, relayUrl)
            .putString(KEY_TOKEN,     idToken)
            .putString(KEY_DEV_NAME,  deviceName)
            .apply()
    }

    fun saveIdToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun getSavedUserId(): String?    = prefs.getString(KEY_USER_ID,   null)
    fun getSavedDeviceId(): String?  = prefs.getString(KEY_DEVICE_ID, null)
    fun getSavedPairId(): String?    = prefs.getString(KEY_PAIR_ID,   null)
    fun getSavedRelayUrl(): String   = prefs.getString(KEY_RELAY_URL, DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL
    fun getSavedIdToken(): String?   = prefs.getString(KEY_TOKEN,     null)
    fun getSavedDeviceName(): String = prefs.getString(KEY_DEV_NAME,  "Phone") ?: "Phone"

    fun clearPairing() {
        prefs.edit().clear().apply()
    }

    fun hasSavedPairing(): Boolean =
        !getSavedUserId().isNullOrEmpty() && !getSavedDeviceId().isNullOrEmpty()
}
