package com.phynex.NexLink.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

object LanDiscoveryClient {
    private const val TAG = "LanDiscovery"
    private const val PORT = 55555
    private var socket: DatagramSocket? = null
    @Volatile var isListening = false

    suspend fun startListening(targetDeviceId: String, onDiscovered: (ip: String, port: Int) -> Unit) {
        if (isListening) return
        isListening = true
        withContext(Dispatchers.IO) {
            try {
                socket = DatagramSocket(null)
                socket?.reuseAddress = true
                socket?.bind(InetSocketAddress(PORT))
                socket?.soTimeout = 0 // block forever

                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                Log.d(TAG, "Listening for NexLink LAN broadcasts on port $PORT...")

                while (isListening) {
                    socket?.receive(packet)
                    val data = String(packet.data, 0, packet.length)
                    try {
                        val json = JSONObject(data)
                        if (json.optString("type") == "nexlink_discovery") {
                            val deviceId = json.optString("deviceId")
                            val port = json.optInt("port", 49152)
                            val ip = packet.address.hostAddress ?: continue
                            
                            if (deviceId == targetDeviceId) {
                                Log.d(TAG, "Discovered target PC on LAN: $ip:$port")
                                onDiscovered(ip, port)
                                // We can stop listening if we want, but for now we keep listening
                                // in case IP changes or we disconnect
                            }
                        }
                    } catch (e: Exception) {
                        // ignore malformed packets
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "UDP Discovery error: ${e.message}")
            } finally {
                stopListening()
            }
        }
    }

    fun stopListening() {
        isListening = false
        try {
            socket?.close()
        } catch (e: Exception) {}
        socket = null
    }
}
