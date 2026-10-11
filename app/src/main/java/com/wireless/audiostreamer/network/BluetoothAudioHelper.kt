package com.wireless.audiostreamer.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reliable RFCOMM pipe for mono PCM between two phones.
 * - Custom UUID (not standard SPP conflict)
 * - Server always listens; client can connect in parallel
 * - Length-prefixed frames so PCM doesn't desync
 */
class BluetoothAudioHelper(
    private val onPcmReceived: (ByteArray) -> Unit,
    private val onConnectionChanged: ((Boolean) -> Unit)? = null
) {
    companion object {
        private const val TAG = "BluetoothAudioHelper"
        // Custom UUID — avoid conflict with classic SPP serial ports
        private val APP_UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        private const val SERVICE_NAME = "WasAudioPipe"
        private const val MAX_FRAME = 8192
    }

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: DataInputStream? = null
    private val serverRunning = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    @Volatile private var stopping = false

    fun isConnected(): Boolean = connected.get() && clientSocket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun startServer(scope: CoroutineScope) {
        if (serverRunning.getAndSet(true)) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth off")
            serverRunning.set(false)
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                // Insecure is more reliable across OEMs for app-to-app
                serverSocket = try {
                    adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
                } catch (_: Exception) {
                    adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
                }
                Log.i(TAG, "BT server listening")
                while (!stopping && serverRunning.get()) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        Log.i(TAG, "BT incoming connection")
                        attachSocket(socket, scope)
                        break // one peer
                    } catch (e: Exception) {
                        if (!stopping) Log.e(TAG, "accept: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "server failed", e)
            } finally {
                serverRunning.set(false)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice, scope: CoroutineScope) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth off")
            onConnectionChanged?.invoke(false)
            return
        }
        // Cancel discovery — speeds up RFCOMM connect
        try { adapter.cancelDiscovery() } catch (_: Exception) {}

        scope.launch(Dispatchers.IO) {
            var socket: BluetoothSocket? = null
            try {
                socket = try {
                    device.createInsecureRfcommSocketToServiceRecord(APP_UUID)
                } catch (_: Exception) {
                    device.createRfcommSocketToServiceRecord(APP_UUID)
                }
                Log.i(TAG, "BT connecting to ${device.address}...")
                socket.connect()
                Log.i(TAG, "BT connected to ${device.name ?: device.address}")
                attachSocket(socket, scope)
            } catch (e: Exception) {
                Log.e(TAG, "client connect failed: ${e.message}")
                try { socket?.close() } catch (_: Exception) {}
                // Fallback reflection channel 1 (some OEMs)
                try {
                    val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                    val s = m.invoke(device, 1) as BluetoothSocket
                    s.connect()
                    Log.i(TAG, "BT connected via fallback channel 1")
                    attachSocket(s, scope)
                } catch (e2: Exception) {
                    Log.e(TAG, "fallback failed", e2)
                    connected.set(false)
                    onConnectionChanged?.invoke(false)
                }
            }
        }
    }

    private fun attachSocket(socket: BluetoothSocket, scope: CoroutineScope) {
        try {
            // Close previous
            try { clientSocket?.close() } catch (_: Exception) {}
            clientSocket = socket
            outputStream = DataOutputStream(socket.outputStream)
            inputStream = DataInputStream(socket.inputStream)
            connected.set(true)
            onConnectionChanged?.invoke(true)
            readLoop(scope)
        } catch (e: Exception) {
            Log.e(TAG, "attach", e)
            connected.set(false)
            onConnectionChanged?.invoke(false)
        }
    }

    private fun readLoop(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val header = ByteArray(4)
            while (!stopping && connected.get()) {
                try {
                    val din = inputStream ?: break
                    din.readFully(header)
                    val len = ByteBuffer.wrap(header).int
                    if (len <= 0 || len > MAX_FRAME) {
                        Log.w(TAG, "bad frame len=$len")
                        continue
                    }
                    val body = ByteArray(len)
                    din.readFully(body)
                    onPcmReceived(body)
                } catch (e: Exception) {
                    if (!stopping) Log.e(TAG, "read: ${e.message}")
                    break
                }
            }
            connected.set(false)
            onConnectionChanged?.invoke(false)
        }
    }

    @Synchronized
    fun send(pcm: ByteArray) {
        if (!connected.get()) return
        try {
            val out = outputStream ?: return
            val header = ByteBuffer.allocate(4).putInt(pcm.size).array()
            out.write(header)
            out.write(pcm)
            out.flush()
        } catch (e: Exception) {
            Log.e(TAG, "send: ${e.message}")
            connected.set(false)
            onConnectionChanged?.invoke(false)
        }
    }

    fun stop() {
        stopping = true
        serverRunning.set(false)
        connected.set(false)
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        clientSocket = null
        serverSocket = null
        onConnectionChanged?.invoke(false)
        Log.i(TAG, "BT stopped")
    }
}
