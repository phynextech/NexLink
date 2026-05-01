package com.phynex.NexLink.service

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages the permanent connection (pairing) between this phone and a PC.
 * Stores the pairId locally so the app can automatically reconnect
 * to the cloud relay server without needing to scan the QR code again.
 */
class PairingManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("NexLinkPairing", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PAIR_ID = "saved_pair_id"
        private const val KEY_IP = "saved_ip"
        private const val KEY_PORT = "saved_port"
        private const val KEY_RELAY_URL = "saved_relay_url"
        private const val KEY_TOKEN = "saved_session_token"
    }

    fun savePairing(pairId: String, ip: String, port: Int, relayUrl: String, token: String) {
        prefs.edit()
            .putString(KEY_PAIR_ID, pairId)
            .putString(KEY_IP, ip)
            .putInt(KEY_PORT, port)
            .putString(KEY_RELAY_URL, relayUrl)
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun getSavedPairId(): String? = prefs.getString(KEY_PAIR_ID, null)
    
    fun getSavedIp(): String? = prefs.getString(KEY_IP, null)
    
    fun getSavedPort(): Int = prefs.getInt(KEY_PORT, 8765)
    
    fun getSavedRelayUrl(): String? = prefs.getString(KEY_RELAY_URL, null)
    
    fun getSavedToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun clearPairing() {
        prefs.edit().clear().apply()
    }
    
    fun hasSavedPairing(): Boolean {
        return !getSavedPairId().isNullOrEmpty()
    }
}
