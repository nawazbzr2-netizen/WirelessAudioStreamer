package com.wireless.audiostreamer.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import java.net.DatagramSocket

/**
 * Forces sockets onto Wi-Fi / hotspot interface only.
 * Never uses mobile data / internet path.
 */
object LocalNetworkBinder {
    private const val TAG = "LocalNetworkBinder"

    fun isPrivateIp(ip: String): Boolean {
        if (ip.isBlank() || ip.contains(":")) return false // skip IPv6 for now
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return when {
            a == 10 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a == 127 -> false
            else -> false
        }
    }

    /** Find active Wi-Fi / hotspot Network (may have NO internet). */
    fun findWifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.allNetworks.firstOrNull { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
                    val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    val notVpn = !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    wifi && notVpn
                }
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "findWifiNetwork", e)
            null
        }
    }

    fun bindSocket(context: Context, socket: DatagramSocket) {
        try {
            val network = findWifiNetwork(context)
            if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                network.bindSocket(socket)
                Log.i(TAG, "Socket bound to WiFi network (local only)")
            } else {
                Log.w(TAG, "No WiFi network to bind — using default")
            }
        } catch (e: Exception) {
            Log.e(TAG, "bindSocket failed", e)
        }
    }

    /** Prefer WiFi network for process when available (even without internet). */
    fun preferWifiNetwork(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = findWifiNetwork(context) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.bindProcessToNetwork(network)
                Log.i(TAG, "Process bound to WiFi (no internet required)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "preferWifiNetwork", e)
        }
    }

    fun clearProcessBinding(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.bindProcessToNetwork(null)
            }
        } catch (_: Exception) {}
    }
}
