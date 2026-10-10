package com.wireless.audiostreamer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.media.AudioManager
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
        const val ACTION_SET_ROLE = "com.wireless.audiostreamer.SET_ROLE"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_CONNECTION_TYPE = "connection_type"
        const val EXTRA_PEER_IP = "peer_ip"
        const val EXTRA_BT_ADDRESS = "bt_address"
        const val EXTRA_ROLE = "role"

        const val ROLE_IDLE = "IDLE"
        const val ROLE_SENDER = "SENDER"
        const val ROLE_RECEIVER = "RECEIVER"

        private const val CHANNEL_ID = "streaming_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "StreamingService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var mediaProjection: MediaProjection? = null
    private var connectionType: String = "WIFI"
    @Volatile private var peerIp: String? = null
    private var btAddress: String? = null
    @Volatile private var role: String = ROLE_IDLE

    private var capturer: SystemAudioCapturer? = null
    private var player: LowLatencyPlayer? = null
    private var udpStreamer: UdpAudioStreamer? = null
    private var udpReceiver: UdpAudioReceiver? = null
    private var discovery: DiscoveryHelper? = null
    private var btHelper: BluetoothAudioHelper? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var previousMusicVolume: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                connectionType = intent.getStringExtra(EXTRA_CONNECTION_TYPE) ?: "WIFI"
                peerIp = intent.getStringExtra(EXTRA_PEER_IP)
                btAddress = intent.getStringExtra(EXTRA_BT_ADDRESS)
                role = intent.getStringExtra(EXTRA_ROLE) ?: ROLE_IDLE

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (data != null) {
                    startForeground(NOTIFICATION_ID, buildNotification("Ready — role choose karo"))
                    initConnection(resultCode, data)
                }
            }
            ACTION_SET_ROLE -> applyRole(intent.getStringExtra(EXTRA_ROLE) ?: ROLE_IDLE)
            ACTION_STOP -> {
                stopEverything()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun initConnection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)
        player = LowLatencyPlayer().also { it.start() }

        when (connectionType) {
            "WIFI" -> setupWifi()
            "BT" -> setupBluetooth()
        }
    }

    private fun setupWifi() {
        udpReceiver = UdpAudioReceiver { fromIp, seq, _, payload ->
            if (peerIp == null || peerIp != fromIp) {
                peerIp = fromIp
                ensureStreamer(fromIp)
                updateNotification("Linked: $fromIp")
            }
            // Always feed jitter buffer when receiving role
            if (role == ROLE_RECEIVER || role == ROLE_IDLE) {
                player?.enqueue(seq, payload)
            }
        }
        udpReceiver?.start(serviceScope)

        peerIp?.let { ensureStreamer(it) }

        discovery = DiscoveryHelper { ip ->
            if (peerIp == null) {
                peerIp = ip
                ensureStreamer(ip)
                updateNotification("Found: $ip")
            }
        }
        discovery?.start(serviceScope)
    }

    private fun ensureStreamer(ip: String) {
        if (udpStreamer == null) {
            udpStreamer = UdpAudioStreamer(ip).also { it.start() }
        } else {
            udpStreamer?.targetIp = ip
        }
    }

    @Suppress("MissingPermission")
    private fun setupBluetooth() {
        btHelper = BluetoothAudioHelper { pcm ->
            if (role == ROLE_RECEIVER || role == ROLE_IDLE) {
                // BT has no seq — synthetic increasing seq
                player?.enqueue((System.nanoTime() % Int.MAX_VALUE).toInt(), pcm)
            }
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        btHelper?.startServer(serviceScope)
        if (btAddress != null) {
            try {
                btHelper?.connectToDevice(adapter.getRemoteDevice(btAddress), serviceScope)
            } catch (e: Exception) {
                Log.e(TAG, "BT", e)
            }
        }
    }

    private fun applyRole(newRole: String) {
        role = newRole
        when (role) {
            ROLE_SENDER -> {
                startSenderCapture()
                muteLocalSpeaker(true)
                updateNotification("SENDER — clean stream on")
            }
            ROLE_RECEIVER -> {
                stopSenderCapture()
                muteLocalSpeaker(false)
                updateNotification("RECEIVER — jitter buffer ~200ms")
            }
            else -> {
                stopSenderCapture()
                muteLocalSpeaker(false)
            }
        }
    }

    private fun startSenderCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val proj = mediaProjection ?: return
        stopSenderCapture()
        capturer = SystemAudioCapturer(proj) { seq, pcm ->
            if (role != ROLE_SENDER) return@SystemAudioCapturer
            when (connectionType) {
                "WIFI" -> {
                    peerIp?.let {
                        ensureStreamer(it)
                        // Packet already has seq in UdpAudioStreamer — pass pcm; streamer adds its own seq
                        // Better: send with capturer seq for jitter reorder
                        udpStreamer?.sendWithSeq(seq, pcm)
                    }
                }
                "BT" -> btHelper?.send(pcm)
            }
        }
        capturer?.start(serviceScope)
    }

    private fun stopSenderCapture() {
        capturer?.stop()
        capturer = null
    }

    private fun muteLocalSpeaker(mute: Boolean) {
        try {
            val am = audioManager ?: return
            if (mute) {
                previousMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            } else if (previousMusicVolume >= 0) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, previousMusicVolume, 0)
                previousMusicVolume = -1
            }
        } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Was::Stream").apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {}
    }

    private fun stopEverything() {
        muteLocalSpeaker(false)
        stopSenderCapture()
        player?.stop(); player = null
        udpStreamer?.stop(); udpStreamer = null
        udpReceiver?.stop(); udpReceiver = null
        discovery?.stop(); discovery = null
        btHelper?.stop(); btHelper = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        role = ROLE_IDLE
        peerIp = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Audio Streaming", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wireless Audio")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {}
    override fun onDestroy() {
        stopEverything()
        serviceScope.cancel()
        super.onDestroy()
    }
}
