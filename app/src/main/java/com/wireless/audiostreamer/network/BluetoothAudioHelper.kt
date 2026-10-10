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
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth RFCOMM audio transfer (not A2DP sink).
 * Works as custom audio pipe between two phones.
 */
class BluetoothAudioHelper(
    private val onPcmReceived: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "BluetoothAudioHelper"
        private val APP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val SERVICE_NAME = "WirelessAudioStreamer"
    }

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun startServer(scope: CoroutineScope) {
        if (running) return
        running = true
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return

        scope.launch(Dispatchers.IO) {
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
                Log.i(TAG, "Bluetooth server listening")
                val socket = serverSocket?.accept()
                if (socket != null) {
                    clientSocket = socket
                    outputStream = socket.outputStream
                    inputStream = socket.inputStream
                    Log.i(TAG, "Bluetooth peer connected (server)")
                    readLoop(scope)
                }
            } catch (e: Exception) {
                Log.e(TAG, "server failed", e)
                running = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice, scope: CoroutineScope) {
        if (running) return
        running = true

        scope.launch(Dispatchers.IO) {
            try {
                val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                socket.connect()
                clientSocket = socket
                outputStream = socket.outputStream
                inputStream = socket.inputStream
                Log.i(TAG, "Bluetooth connected to ${device.name}")
                readLoop(scope)
            } catch (e: Exception) {
                Log.e(TAG, "client connect failed", e)
                running = false
            }
        }
    }

    private fun readLoop(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            while (isActive && running) {
                try {
                    val read = inputStream?.read(buffer) ?: -1
                    if (read > 0) {
                        onPcmReceived(buffer.copyOf(read))
                    } else if (read < 0) {
                        break
                    }
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "read error", e)
                    break
                }
            }
        }
    }

    fun send(pcm: ByteArray) {
        try {
            outputStream?.write(pcm)
            outputStream?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "send failed", e)
        }
    }

    fun isConnected(): Boolean = clientSocket?.isConnected == true

    fun stop() {
        running = false
        try {
            inputStream?.close()
            outputStream?.close()
            clientSocket?.close()
            serverSocket?.close()
        } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        clientSocket = null
        serverSocket = null
        Log.i(TAG, "Bluetooth stopped")
    }
}
