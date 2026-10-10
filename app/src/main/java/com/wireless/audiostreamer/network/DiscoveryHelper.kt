package com.wireless.audiostreamer.network

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Local LAN discovery only — no internet. */
class DiscoveryHelper(
    private val context: Context? = null,
    private val onPeerFound: ((ip: String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "DiscoveryHelper"
        private const val DISCOVERY_PORT = 50001
        private const val HELLO = "WAS_HELLO"
        private const val REPLY = "WAS_REPLY"

        fun localIp(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (!iface.isUp || iface.isLoopback) continue
                    val name = iface.name.lowercase()
                    // Prefer wlan / softap / ap interfaces (WiFi / hotspot)
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        val ip = addr.hostAddress ?: continue
                        if (addr.isLoopbackAddress || ip.contains(":")) continue
                        if (LocalNetworkBinder.isPrivateIp(ip)) {
                            if (name.contains("wlan") || name.contains("ap") ||
                                name.contains("soft") || name.contains("wifi")
                            ) {
                                return ip
                            }
                        }
                    }
                }
                // fallback any private
                val interfaces2 = NetworkInterface.getNetworkInterfaces()
                while (interfaces2.hasMoreElements()) {
                    val iface = interfaces2.nextElement()
                    if (!iface.isUp || iface.isLoopback) continue
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        val ip = addr.hostAddress ?: continue
                        if (!addr.isLoopbackAddress && LocalNetworkBinder.isPrivateIp(ip)) {
                            return ip
                        }
                    }
                }
            } catch (_: Exception) {}
            return "0.0.0.0"
        }
    }

    private var socket: DatagramSocket? = null
    @Volatile private var running = false

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        try {
            socket = DatagramSocket(null)
            socket?.reuseAddress = true
            socket?.broadcast = true
            socket?.bind(InetSocketAddress(DISCOVERY_PORT))
            context?.let { LocalNetworkBinder.bindSocket(it, socket!!) }

            scope.launch(Dispatchers.IO) {
                val buf = ByteArray(128)
                while (isActive && running) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket?.receive(packet)
                        val msg = String(packet.data, 0, packet.length)
                        val ip = packet.address?.hostAddress ?: continue
                        if (!LocalNetworkBinder.isPrivateIp(ip)) continue
                        if (ip == localIp()) continue

                        when {
                            msg.startsWith(HELLO) -> {
                                sendRaw(REPLY.toByteArray(), ip)
                                onPeerFound?.invoke(ip)
                            }
                            msg.startsWith(REPLY) -> onPeerFound?.invoke(ip)
                        }
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "listen: ${e.message}")
                    }
                }
            }

            scope.launch(Dispatchers.IO) {
                while (isActive && running) {
                    try { broadcast(HELLO.toByteArray()) } catch (_: Exception) {}
                    delay(1000)
                }
            }
            Log.i(TAG, "Local discovery started")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            running = false
        }
    }

    private fun broadcast(data: ByteArray) {
        // Limited broadcast on local subnet
        val packet = DatagramPacket(
            data, data.size,
            InetAddress.getByName("255.255.255.255"),
            DISCOVERY_PORT
        )
        socket?.send(packet)
    }

    private fun sendRaw(data: ByteArray, ip: String) {
        if (!LocalNetworkBinder.isPrivateIp(ip)) return
        val packet = DatagramPacket(
            data, data.size,
            InetAddress.getByName(ip),
            DISCOVERY_PORT
        )
        socket?.send(packet)
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    fun getLocalIp(): String = localIp()
}
