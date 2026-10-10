package com.wireless.audiostreamer.network

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class UdpAudioStreamer(
    private val context: Context,
    @Volatile var targetIp: String,
    private val port: Int = 50000
) {
    companion object {
        private const val TAG = "UdpAudioStreamer"
    }

    private var socket: DatagramSocket? = null
    private val fallbackSeq = AtomicInteger(0)
    @Volatile var packetsSent = 0

    fun start() {
        try {
            // ONLY private LAN IPs
            if (!LocalNetworkBinder.isPrivateIp(targetIp)) {
                Log.e(TAG, "Blocked non-private IP: $targetIp (internet not allowed)")
                return
            }
            socket = DatagramSocket()
            LocalNetworkBinder.bindSocket(context, socket!!)
            socket?.broadcast = true
            Log.i(TAG, "LOCAL TX -> $targetIp:$port")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
        }
    }

    fun send(payload: ByteArray) {
        sendWithSeq(fallbackSeq.getAndIncrement(), payload)
    }

    fun sendWithSeq(sequence: Int, payload: ByteArray) {
        val ip = targetIp
        if (!LocalNetworkBinder.isPrivateIp(ip)) return
        try {
            val packetData = ByteBuffer.allocate(12 + payload.size)
                .putInt(sequence)
                .putLong(System.nanoTime())
                .put(payload)
                .array()
            socket?.send(
                DatagramPacket(packetData, packetData.size, InetAddress.getByName(ip), port)
            )
            packetsSent++
        } catch (e: Exception) {
            if (packetsSent % 100 == 0) Log.e(TAG, "send: ${e.message}")
        }
    }

    fun stop() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}
