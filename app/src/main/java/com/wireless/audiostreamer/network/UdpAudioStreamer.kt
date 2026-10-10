package com.wireless.audiostreamer.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class UdpAudioStreamer(
    private val targetIp: String,
    private val port: Int = 50000
) {
    companion object {
        private const val TAG = "UdpAudioStreamer"
    }

    private var socket: DatagramSocket? = null
    private var sequence = 0

    fun start() {
        try {
            socket = DatagramSocket()
            socket?.broadcast = true
            Log.i(TAG, "Streamer started -> $targetIp:$port")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
        }
    }

    fun send(payload: ByteArray) {
        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "send failed", e)
        }
    }

    fun stop() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        Log.i(TAG, "Streamer stopped")
    }
}
