package com.phynex.NexLink.websocket

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.net.URI

/**
 * NexLink Socket.IO client — cloud-only architecture.
 *
 * Connects to the Render relay server over HTTPS/WSS using Socket.IO.
 * All legacy local-WiFi (ws://192.168.x.x) logic has been removed.
 *
 * Connection flow:
 *   1. connect(relayUrl, userId, deviceId, firebaseIdToken)
 *   2. On Socket.IO 'connect': emit 'connect_device' { userId, deviceId, role="mobile" }
 *   3. Server places socket in room userId:deviceId
 *   4. All events are forwarded to the laptop in the same room
 *   5. On disconnect: auto-retry every 5 seconds using saved session
 */
class NexLinkSocketClient {
    private val TAG = "NexLinkSocket"
    private val gson = Gson()

    private var socket: Socket? = null
    private var relayUrl: String = "https://nexlink-khhe.onrender.com"
    private var userId: String = ""
    private var deviceId: String = ""
    private var firebaseToken: String = ""
    private var isManualDisconnect = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val listeners = mutableMapOf<String, (JsonObject) -> Unit>()

    // ── Public state flows ─────────────────────────────────────────────────
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _isPeerOnline = MutableStateFlow(false)
    val isPeerOnline: StateFlow<Boolean> = _isPeerOnline

    private val _connectionMode = MutableStateFlow("Disconnected")
    val connectionMode: StateFlow<String> = _connectionMode

    private val _lastMessage = MutableStateFlow<JsonObject?>(null)
    val lastMessage: StateFlow<JsonObject?> = _lastMessage

    // ─── Public API ────────────────────────────────────────────────────────

    fun addListener(type: String, callback: (JsonObject) -> Unit) {
        listeners[type] = callback
    }

    /** Connect (or reconnect) to the relay server. Token refreshes are handled externally. */
    fun connect(
        relay: String = "https://nexlink-khhe.onrender.com",
        uid: String,
        did: String,
        token: String,
    ) {
        isManualDisconnect = false
        relayUrl      = relay
        userId        = uid
        deviceId      = did
        firebaseToken = token

        buildAndConnect()
    }

    fun disconnect() {
        isManualDisconnect = true
        socket?.disconnect()
        socket = null
        _isConnected.value    = false
        _isPeerOnline.value   = false
        _connectionMode.value = "Disconnected"
    }

    fun sendMessage(data: Map<String, Any>) {
        val event = data["type"] as? String ?: return
        val json  = JSONObject(data.filter { it.key != "type" })
        try {
            socket?.emit(event, json) ?: Log.w(TAG, "Socket not connected — drop: $event")
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed: ${e.message}")
        }
    }

    fun sendRaw(event: String, json: JSONObject) {
        try { socket?.emit(event, json) } catch (_: Exception) {}
    }

    fun reconnect() {
        if (!isManualDisconnect && !_isConnected.value) {
            buildAndConnect()
        }
    }

    // ─── Internal ──────────────────────────────────────────────────────────

    private fun buildAndConnect() {
        socket?.disconnect()
        socket?.off()

        _connectionMode.value = "Connecting…"
        Log.d(TAG, "Connecting to $relayUrl  uid=$userId  device=$deviceId")

        try {
            val opts = IO.Options.builder()
                .setTransports(arrayOf("polling", "websocket"))  // polling first, then upgrade
                .setReconnection(true)
                .setReconnectionDelay(5000)
                .setReconnectionAttempts(Int.MAX_VALUE)
                .setTimeout(20000)
                .setAuth(mapOf("token" to firebaseToken, "userId" to userId))
                .build()

            val sock = IO.socket(URI.create(relayUrl), opts)
            socket = sock

            sock.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "Socket.IO connected  id=${sock.id()}")
                _isConnected.value   = true
                _connectionMode.value = "Cloud Relay"

                // Register this device in its room
                val payload = JSONObject().apply {
                    put("userId",     userId)
                    put("deviceId",   deviceId)
                    put("role",       "mobile")
                    put("deviceName", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                }
                sock.emit("connect_device", payload)

                // Delayed request_info — in case PC was already connected when we joined
                // Wait 1.5s for registration to propagate on the relay before asking
                scope.launch {
                    delay(1500)
                    // Emit as named event (server relays by event name, not by "message")
                    sock.emit("request_info", JSONObject())
                    Log.d(TAG, "Sent request_info on connect (delayed 1.5s)")
                    delay(500)
                    sock.emit("get_wallpaper", JSONObject())
                    Log.d(TAG, "Sent get_wallpaper on connect")
                }
            }

            sock.on(Socket.EVENT_DISCONNECT) { args ->
                val reason = args?.firstOrNull()?.toString() ?: "unknown"
                Log.w(TAG, "Disconnected: $reason")
                _isConnected.value    = false
                _isPeerOnline.value   = false
                _connectionMode.value = "Reconnecting…"
                // Socket.IO handles auto-reconnect via setReconnection(true)
            }

            sock.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val err = args?.firstOrNull()?.toString() ?: "unknown"
                Log.e(TAG, "Connect error: $err")
                _isConnected.value    = false
                _isPeerOnline.value   = false
                _connectionMode.value = "Connection Error"
            }

            sock.on("peer_online") { _ ->
                Log.d(TAG, "Peer (laptop) came online")
                _isPeerOnline.value = true

                // Request all real system state from Windows immediately
                // Emit as named events — server relays by event name, not by generic "message"
                scope.launch {
                    delay(300)
                    sock.emit("request_info", JSONObject())
                    Log.d(TAG, "Sent request_info to PC after peer came online")
                    delay(500)
                    sock.emit("get_wallpaper", JSONObject())
                    Log.d(TAG, "Sent get_wallpaper to PC after peer came online")
                }
            }

            sock.on("peer_offline") { _ ->
                Log.d(TAG, "Peer (laptop) went offline")
                _isPeerOnline.value = false
            }

            sock.on("device_registered") { args ->
                val data = args?.firstOrNull() as? JSONObject ?: return@on
                Log.d(TAG, "Device registered in room: ${data.optString("roomKey")}")
            }

            sock.on("error") { args ->
                val msg = (args?.firstOrNull() as? JSONObject)?.optString("message") ?: "Server error"
                Log.e(TAG, "Server error: $msg")
                val json = JsonObject().apply { addProperty("type", "error"); addProperty("message", msg) }
                listeners["error"]?.invoke(json)
            }

            // ── Relay all known event types to registered listeners ───────
            ALL_EVENTS.forEach { event ->
                sock.on(event) { args ->
                    val raw = args?.firstOrNull()
                    val jsonObj: JsonObject = when (raw) {
                        is JSONObject -> parseJsonObject(raw.toString())
                        is String     -> parseJsonObject(raw)
                        else          -> JsonObject().also { it.addProperty("type", event) }
                    }
                    if (!jsonObj.has("type")) jsonObj.addProperty("type", event)
                    _lastMessage.value = jsonObj
                    try {
                        listeners[event]?.invoke(jsonObj)
                    } catch (e: Exception) {
                        Log.e(TAG, "Listener crash for event '$event': ${e.message}")
                    }
                }
            }

            sock.connect()
        } catch (e: Exception) {
            Log.e(TAG, "buildAndConnect failed: ${e.message}")
            _connectionMode.value = "Error"
        }
    }

    private fun parseJsonObject(text: String): JsonObject {
        return try {
            JsonParser.parseString(text).asJsonObject
        } catch (_: Exception) {
            JsonObject()
        }
    }

    companion object {
        /** All events the server may push to this mobile client */
        val ALL_EVENTS = listOf(
            "wifi_info", "battery_info", "bt_info", "wallpaper",
            "now_playing", "volume", "brightness",
            "volume_ack", "brightness_ack",
            "system_state", "state_update",
            "app_list", "file_list", "file_chunk", "file_preview_data",
            "clipboard_pull", "clipboard_push", "clipboard_sync",
            "notification", "send_notification",
            "sms_list", "sms_received",
            "screen_frame", "camera_frame",
            "webrtc_offer", "webrtc_answer", "webrtc_ice",
            "peer_online", "peer_offline",
            "usb_connected", "usb_disconnected",
            "error", "pong",
        )
    }
}
