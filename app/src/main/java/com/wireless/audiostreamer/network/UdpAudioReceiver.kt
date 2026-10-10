package com.wireless.audiostreamer.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class UdpAudioReceiver(
    private val port: Int = 50000,
    private val onPacket: (fromIp: String, sequence: Int, timestamp: Long, payload: ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "UdpAudioReceiver"
    }

    private var socket: DatagramSocket? = null
    @Volatile private var running = false

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        try {
            socket = DatagramSocket(port)
            socket?.broadcast = true
            socket?.reuseAddress = true
            Log.i(TAG, "Listening on $port")

            scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(16384)
                while (isActive && running) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket?.receive(packet)
                        if (packet.length >= 12) {
                            val fromIp = packet.address.hostAddress ?: continue
                            val bb = ByteBuffer.wrap(packet.data, 0, packet.length)
                            val seq = bb.int
                            val ts = bb.long
                            val payload = ByteArray(packet.length - 12)
                            bb.get(payload)
                            onPacket(fromIp, seq, ts, payload)
                        }
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "receive: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            running = false
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}
