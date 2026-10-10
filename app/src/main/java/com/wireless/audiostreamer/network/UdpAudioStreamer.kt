package com.wireless.audiostreamer.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class UdpAudioStreamer(
    @Volatile var targetIp: String,
    private val port: Int = 50000
) {
    companion object {
        private const val TAG = "UdpAudioStreamer"
    }

    private var socket: DatagramSocket? = null
    private val fallbackSeq = AtomicInteger(0)

    fun start() {
        try {
            socket = DatagramSocket()
            socket?.broadcast = true
            Log.i(TAG, "Streamer -> $targetIp:$port")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
        }
    }

    fun send(payload: ByteArray) {
        sendWithSeq(fallbackSeq.getAndIncrement(), payload)
    }

    fun sendWithSeq(sequence: Int, payload: ByteArray) {
        val ip = targetIp
        if (ip.isBlank() || ip == "0.0.0.0") return
        try {
            val packetData = ByteBuffer.allocate(12 + payload.size)
                .putInt(sequence)
                .putLong(System.nanoTime())
                .put(payload)
                .array()

            socket?.send(
                DatagramPacket(
                    packetData, packetData.size,
                    InetAddress.getByName(ip), port
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "send: ${e.message}")
        }
    }

    fun stop() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}
