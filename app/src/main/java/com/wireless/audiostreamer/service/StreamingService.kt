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
import android.os.PowerManager
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
    private var connectionType: String = "SAME_WIFI"

    private var capturer: SystemAudioCapturer? = null
    private var player: LowLatencyPlayer? = null
    private var udpStreamer: UdpAudioStreamer? = null
    private var udpReceiver: UdpAudioReceiver? = null
    private var discovery: DiscoveryHelper? = null
    private var btHelper: BluetoothAudioHelper? = null

    private var peerIp: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                connectionType = intent.getStringExtra(EXTRA_CONNECTION_TYPE) ?: "SAME_WIFI"
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (data != null) {
                    startForeground(NOTIFICATION_ID, buildNotification("Searching peer..."))
                    startAll(resultCode, data)
                }
            }
            ACTION_STOP -> {
                stopEverything()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // Sticky = system kill kare to dobara start karne ki koshish
        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "WirelessAudio::StreamingWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L) // 1 hour max, renew as needed
            }
            Log.i(TAG, "WakeLock acquired - screen off pe bhi chalega")
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock failed", e)
        }
    }

    private fun startAll(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        // Always ready to play incoming audio
        player = LowLatencyPlayer().also { it.start() }

        // Always listen + advertise so peer auto-connect ho jaye
        when (connectionType) {
            "SAME_WIFI", "HOTSPOT" -> startWifiPath()
            "BLUETOOTH" -> startBluetoothPath()
        }

        // Capture THIS phone's system audio and send to peer
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
            capturer = SystemAudioCapturer(mediaProjection!!) { pcm ->
                when (connectionType) {
                    "SAME_WIFI", "HOTSPOT" -> {
                        val ip = peerIp
                        if (ip != null) {
                            if (udpStreamer == null) {
                                udpStreamer = UdpAudioStreamer(ip).also { it.start() }
                            }
                            udpStreamer?.send(pcm)
                        }
                    }
                    "BLUETOOTH" -> btHelper?.send(pcm)
                }
            }
            capturer?.start(serviceScope)
        }

        updateNotification("Running - peer dhoondh raha hai...")
    }

    private fun startWifiPath() {
        // Receive from anyone on network
        udpReceiver = UdpAudioReceiver { _, _, payload ->
            player?.write(payload)
        }
        udpReceiver?.start(serviceScope)

        // Auto discover - jab peer mile to connect
        discovery = DiscoveryHelper { ip ->
            if (peerIp == null || peerIp != ip) {
                peerIp = ip
                Log.i(TAG, "Auto connected to peer: $ip")
                udpStreamer?.stop()
                udpStreamer = UdpAudioStreamer(ip).also { it.start() }
                updateNotification("Connected: $ip")
            }
        }
        discovery?.start(serviceScope)
    }

    @Suppress("MissingPermission")
    private fun startBluetoothPath() {
        btHelper = BluetoothAudioHelper { pcm ->
            player?.write(pcm)
        }

        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return

        // Always listen as server so dusra phone connect kar sake
        btHelper?.startServer(serviceScope)

        // Try all paired devices automatically
        try {
            adapter.bondedDevices?.forEach { device ->
                Log.i(TAG, "Auto try BT: ${device.name}")
                btHelper?.connectToDevice(device, serviceScope)
            }
        } catch (e: Exception) {
            Log.e(TAG, "BT bonded error", e)
        }
    }

    private fun stopEverything() {
        capturer?.stop(); capturer = null
        player?.stop(); player = null
        udpStreamer?.stop(); udpStreamer = null
        udpReceiver?.stop(); udpReceiver = null
        discovery?.stop(); discovery = null
        btHelper?.stop(); btHelper = null
        mediaProjection?.stop(); mediaProjection = null
        peerIp = null

        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null

        Log.i(TAG, "All stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps audio streaming alive in background"
                setShowBadge(false)
            }
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
            .setContentTitle("Wireless Audio Connected")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    // App task remove hone pe bhi service na mare (jab tak STOP na aaye)
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Task removed but service keeps running")
        // Do NOT stop
    }

    override fun onDestroy() {
        stopEverything()
        serviceScope.cancel()
        super.onDestroy()
    }
}
