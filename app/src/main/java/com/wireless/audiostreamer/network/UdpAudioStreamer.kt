package com.wireless.audiostreamer.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Simple UDP sender for Opus (or raw PCM) packets.
 * Packet format: [seq:4][timestamp:8][payload...]
 */
class UdpAudioStreamer(
    private val targetIp: String,
    private val port: Int = 50000
) {
    private var socket: DatagramSocket? = null
    private var sequence = 0

    fun start() {
        socket = DatagramSocket()
    }

    fun send(payload: ByteArray) {
        val packetData = ByteBuffer.allocate(12 + payload.size)
            .putInt(sequence++)
            .putLong(System.nanoTime())
            .put(payload)
            .array()

        val packet = DatagramPacket(
            packetData,
            packetData.size,
            InetAddress.getByName(targetIp),
            port
        )
        socket?.send(packet)
    }

    fun stop() {
        socket?.close()
        socket = null
    }
}
