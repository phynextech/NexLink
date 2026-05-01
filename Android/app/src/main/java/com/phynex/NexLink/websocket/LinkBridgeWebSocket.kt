package com.phynex.NexLink.websocket

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * NexLink WebSocket client with dual-mode support:
 *
 * LOCAL mode  — Connects directly to PC via LAN IP (fast, no internet needed)
 * RELAY mode  — Connects via NexLink cloud relay server (works from anywhere)
 *
 * On connect: tries LOCAL first (fast). If that fails within 5s, falls back to RELAY.
 * Connection info is embedded in the QR code: { ip, port, pairId, relayUrl }
 */
class LinkBridgeWebSocket {
    private val TAG = "LinkBridgeWS"
    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null

    // Connection params
    private var localUrl: String = ""       // ws://192.168.x.x:8765
    private var relayUrl: String = ""       // wss://nexlink-relay.onrender.com/relay?...
    private var sessionToken: String = ""
    private var pairId: String = ""

    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 100
    private var isManualDisconnect = false
    private var isRelayMode = false

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _connectionMode = MutableStateFlow("Disconnected")
    val connectionMode: StateFlow<String> = _connectionMode

    private val _lastMessage = MutableStateFlow<JsonObject?>(null)
    val lastMessage: StateFlow<JsonObject?> = _lastMessage

    private val listeners = mutableMapOf<String, (JsonObject) -> Unit>()

    fun addListener(type: String, callback: (JsonObject) -> Unit) {
        listeners[type] = callback
    }

    // ─── Connect: tries local first, then relay ───────────────────────────
    fun connect(ip: String, port: Int, token: String, pair: String = "", relay: String = "") {
        isManualDisconnect = false
        localUrl = "ws://$ip:$port"
        sessionToken = token
        pairId = pair
        relayUrl = if (relay.isNotEmpty()) "$relay?pairId=$pair&role=mobile"
                   else "wss://nexlink-relay.onrender.com/relay?pairId=$pair&role=mobile"

        isRelayMode = false
        reconnectAttempts = 0
        buildClient()
        connectLocal()
    }

    private fun buildClient() {
        client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ─── LOCAL connection attempt ─────────────────────────────────────────
    private fun connectLocal() {
        Log.d(TAG, "Trying LOCAL: $localUrl")
        _connectionMode.value = "Connecting (Local)…"
        val request = Request.Builder()
            .url(localUrl)
            .addHeader("X-Session-Token", sessionToken)
            .build()

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "LOCAL connected")
                isRelayMode = false
                _isConnected.value = true
                _connectionMode.value = "Local WiFi"
                reconnectAttempts = 0
                sendHandshake(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text, webSocket)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                handleBinaryMessage(bytes)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "LOCAL failed: ${t.message} — trying RELAY")
                _isConnected.value = false
                if (!isManualDisconnect) {
                    // LOCAL failed → try relay
                    scope.launch { connectRelay() }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _isConnected.value = false
                if (!isManualDisconnect) scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                try { webSocket.close(1000, null) } catch (_: Exception) {}
            }
        })
    }

    // ─── RELAY connection ─────────────────────────────────────────────────
    private fun connectRelay() {
        if (pairId.isEmpty()) {
            Log.e(TAG, "No pairId — cannot use relay")
            return
        }
        Log.d(TAG, "Trying RELAY: $relayUrl")
        _connectionMode.value = "Connecting (Relay)…"

        val request = Request.Builder()
            .url(relayUrl)
            .build()

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "RELAY connected")
                isRelayMode = true
                _isConnected.value = true
                _connectionMode.value = "Cloud Relay"
                reconnectAttempts = 0
                sendHandshake(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text, webSocket)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                handleBinaryMessage(bytes)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "RELAY failed: ${t.message}")
                _isConnected.value = false
                if (!isManualDisconnect) scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _isConnected.value = false
                if (!isManualDisconnect) scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                try { webSocket.close(1000, null) } catch (_: Exception) {}
            }
        })
    }

    private fun sendHandshake(ws: WebSocket) {
        val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        val json = gson.toJson(mapOf("type" to "handshake", "device" to deviceName))
        try { ws.send(json) } catch (_: Exception) {}
    }

    private fun handleMessage(text: String, ws: WebSocket) {
        try {
            val json = JsonParser.parseString(text).asJsonObject
            val type = json.get("type")?.asString ?: ""

            when (type) {
                "ping" -> {
                    try { ws.send("{\"type\":\"pong\"}") } catch (_: Exception) {}
                    return
                }
                "relay_peer_online" -> {
                    Log.d(TAG, "Relay: PC came online")
                    return
                }
                "relay_peer_offline" -> {
                    Log.d(TAG, "Relay: PC went offline")
                    return
                }
                "pong" -> return
            }

            _lastMessage.value = json
            listeners[type]?.invoke(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}")
        }
    }

    private fun handleBinaryMessage(bytes: okio.ByteString) {
        listeners["binary"]?.let {
            val json = JsonObject()
            json.addProperty("type", "binary")
            json.addProperty("data", bytes.base64())
            it(json)
        }
    }

    // ─── Reconnect logic ──────────────────────────────────────────────────
    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            Log.e(TAG, "Max reconnect attempts reached")
            _connectionMode.value = "Disconnected"
            return
        }
        reconnectAttempts++
        val delayMs = (3000L * reconnectAttempts).coerceAtMost(30000L)
        Log.d(TAG, "Reconnect in ${delayMs}ms (attempt $reconnectAttempts)")
        scope.launch {
            delay(delayMs)
            if (!isManualDisconnect && !_isConnected.value) {
                // Always try local first on reconnect
                connectLocal()
            }
        }
    }

    fun reconnect() {
        if (!isManualDisconnect && !_isConnected.value) {
            reconnectAttempts = 0
            connectLocal()
        }
    }

    // ─── Send ─────────────────────────────────────────────────────────────
    fun sendMessage(data: Map<String, Any>) {
        val json = gson.toJson(data)
        try {
            webSocket?.send(json) ?: Log.w(TAG, "WebSocket not connected")
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}")
        }
    }

    fun sendRaw(json: String) {
        try { webSocket?.send(json) } catch (_: Exception) {}
    }

    fun disconnect() {
        isManualDisconnect = true
        try { webSocket?.close(1000, "User disconnected") } catch (_: Exception) {}
        webSocket = null
        _isConnected.value = false
        _connectionMode.value = "Disconnected"
    }
}
