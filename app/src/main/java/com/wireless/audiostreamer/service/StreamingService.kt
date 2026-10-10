package com.wireless.audiostreamer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wireless.audiostreamer.audio.LowLatencyPlayer
import com.wireless.audiostreamer.audio.SystemAudioCapturer
import com.wireless.audiostreamer.network.BluetoothAudioHelper
import com.wireless.audiostreamer.network.DiscoveryHelper
import com.wireless.audiostreamer.network.UdpAudioReceiver
import com.wireless.audiostreamer.network.UdpAudioStreamer
import com.wireless.audiostreamer.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class StreamingService : Service() {

    companion object {
        const val ACTION_START = "com.wireless.audiostreamer.START"
        const val ACTION_STOP = "com.wireless.audiostreamer.STOP"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_CONNECTION_TYPE = "connection_type"

        private const val CHANNEL_ID = "streaming_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "StreamingService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var mediaProjection: MediaProjection? = null
    private var connectionType: String = "NONE"

    private var capturer: SystemAudioCapturer? = null
    private var player: LowLatencyPlayer? = null
    private var udpStreamer: UdpAudioStreamer? = null
    private var udpReceiver: UdpAudioReceiver? = null
    private var discovery: DiscoveryHelper? = null
    private var btHelper: BluetoothAudioHelper? = null

    private var peerIp: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                connectionType = intent.getStringExtra(EXTRA_CONNECTION_TYPE) ?: "NONE"
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (data != null) {
                    startForeground(NOTIFICATION_ID, buildNotification("Connecting ($connectionType)..."))
                    startAll(resultCode, data)
                }
            }
            ACTION_STOP -> {
                stopEverything()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startAll(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        player = LowLatencyPlayer().also { it.start() }

        when (connectionType) {
            "SAME_WIFI", "HOTSPOT" -> startWifiPath()
            "BLUETOOTH" -> startBluetoothPath()
            else -> Log.w(TAG, "Unknown connection type: $connectionType")
        }

        // Capture system audio and send to peer
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
            capturer = SystemAudioCapturer(mediaProjection!!) { pcm ->
                // Send outgoing audio
                when (connectionType) {
                    "SAME_WIFI", "HOTSPOT" -> {
                        peerIp?.let { ip ->
                            if (udpStreamer == null) {
                                udpStreamer = UdpAudioStreamer(ip).also { it.start() }
                            }
                            udpStreamer?.send(pcm)
                        }
                    }
                    "BLUETOOTH" -> {
                        btHelper?.send(pcm)
                    }
                }
            }
            capturer?.start(serviceScope)
        }

        updateNotification("Streaming ($connectionType)")
    }

    private fun startWifiPath() {
        // Receive audio from peer
        udpReceiver = UdpAudioReceiver { _, _, payload ->
            player?.write(payload)
        }
        udpReceiver?.start(serviceScope)

        // Discover peer on same network / hotspot
        discovery = DiscoveryHelper { ip ->
            if (peerIp == null) {
                peerIp = ip
                Log.i(TAG, "Peer found: $ip")
                udpStreamer = UdpAudioStreamer(ip).also { it.start() }
                updateNotification("Connected to $ip")
            }
        }
        discovery?.start(serviceScope)
    }

    @Suppress("MissingPermission")
    private fun startBluetoothPath() {
        btHelper = BluetoothAudioHelper { pcm ->
            player?.write(pcm)
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Log.e(TAG, "No Bluetooth adapter")
            return
        }

        // Start as server (listen)
        btHelper?.startServer(serviceScope)

        // Also try connect to already paired devices
        try {
            val bonded: Set<BluetoothDevice>? = adapter.bondedDevices
            bonded?.forEach { device ->
                Log.i(TAG, "Trying bonded device: ${device.name}")
                // Try connect in background; first successful wins
                btHelper?.connectToDevice(device, serviceScope)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth bonded devices error", e)
        }
    }

    private fun stopEverything() {
        capturer?.stop()
        capturer = null

        player?.stop()
        player = null

        udpStreamer?.stop()
        udpStreamer = null

        udpReceiver?.stop()
        udpReceiver = null

        discovery?.stop()
        discovery = null

        btHelper?.stop()
        btHelper = null

        mediaProjection?.stop()
        mediaProjection = null

        peerIp = null
        Log.i(TAG, "All stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Streaming",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wireless Audio")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopEverything()
        serviceScope.cancel()
        super.onDestroy()
    }
}
