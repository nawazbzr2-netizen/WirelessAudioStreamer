package com.wireless.audiostreamer.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * UDP receiver. Parses [seq:4][timestamp:8][payload...] and delivers payload.
 */
class UdpAudioReceiver(
    private val port: Int = 50000,
    private val onPacket: (sequence: Int, timestamp: Long, payload: ByteArray) -> Unit
) {
    private var socket: DatagramSocket? = null
    private var running = false

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        socket = DatagramSocket(port)

        scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(2048)
            while (isActive && running) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket?.receive(packet)
                    if (packet.length >= 12) {
                        val bb = ByteBuffer.wrap(packet.data, 0, packet.length)
                        val seq = bb.int
                        val ts = bb.long
                        val payload = ByteArray(packet.length - 12)
                        bb.get(payload)
                        onPacket(seq, ts, payload)
                    }
                } catch (e: Exception) {
                    if (running) e.printStackTrace()
                }
            }
        }
    }

    fun stop() {
        running = false
        socket?.close()
        socket = null
    }
}
