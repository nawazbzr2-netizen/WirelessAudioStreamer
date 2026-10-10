package com.wireless.audiostreamer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StreamingService : Service() {

    companion object {
        const val ACTION_START = "com.wireless.audiostreamer.START"
        const val ACTION_STOP = "com.wireless.audiostreamer.STOP"
        const val ACTION_SET_ROLE = "com.wireless.audiostreamer.SET_ROLE"
        const val ACTION_SET_PEER = "com.wireless.audiostreamer.SET_PEER"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_CONNECTION_TYPE = "connection_type"
        const val EXTRA_PEER_IP = "peer_ip"
        const val EXTRA_BT_ADDRESS = "bt_address"
        const val EXTRA_ROLE = "role"

        const val ROLE_BOTH = "BOTH"
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
    @Volatile private var role: String = ROLE_BOTH

    private var capturer: SystemAudioCapturer? = null
    private var player: LowLatencyPlayer? = null
    private var udpStreamer: UdpAudioStreamer? = null
    private var udpReceiver: UdpAudioReceiver? = null
    private var discovery: DiscoveryHelper? = null
    private var btHelper: BluetoothAudioHelper? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var audioManager: AudioManager? = null
    private var previousMusicVolume: Int = -1
    private var started = false
    @Volatile private var rxCount = 0
    @Volatile private var txHint = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        acquireWakeLock()
        acquireMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("WasMulticast").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "MulticastLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "MulticastLock failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (started) {
                    intent.getStringExtra(EXTRA_PEER_IP)?.let { setPeerIp(it) }
                    return START_STICKY
                }
                connectionType = intent.getStringExtra(EXTRA_CONNECTION_TYPE) ?: "WIFI"
                peerIp = intent.getStringExtra(EXTRA_PEER_IP)
                btAddress = intent.getStringExtra(EXTRA_BT_ADDRESS)
                role = intent.getStringExtra(EXTRA_ROLE) ?: ROLE_BOTH

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (data != null) {
                    startForeground(NOTIFICATION_ID, buildNotification("Starting audio..."))
                    initConnection(resultCode, data)
                    started = true
                }
            }
            ACTION_SET_PEER -> {
                val ip = intent.getStringExtra(EXTRA_PEER_IP)
                val bt = intent.getStringExtra(EXTRA_BT_ADDRESS)
                val type = intent.getStringExtra(EXTRA_CONNECTION_TYPE)
                if (type != null) connectionType = type
                if (ip != null) setPeerIp(ip)
                if (bt != null) {
                    btAddress = bt
                    connectionType = "BT"
                    connectBluetooth(bt)
                }
            }
            ACTION_SET_ROLE -> applyRole(intent.getStringExtra(EXTRA_ROLE) ?: ROLE_BOTH)
            ACTION_STOP -> {
                stopEverything()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun setPeerIp(ip: String) {
        if (ip.isBlank()) return
        peerIp = ip
        // Always recreate streamer for clean socket
        udpStreamer?.stop()
        udpStreamer = UdpAudioStreamer(ip).also { it.start() }
        Log.i(TAG, "PEER LOCKED: $ip")

        // Force capture restart so TX definitely runs
        if (role == ROLE_BOTH || role == ROLE_SENDER) {
            stopSenderCapture()
            startSenderCapture()
        }
        updateNotification("Connected $ip | RX:$rxCount")
    }

    private fun initConnection(resultCode: Int, data: Intent) {
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                updateNotification("Screen cast fail")
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "projection", e)
            updateNotification("Screen cast error")
            return
        }

        val jitterMs = if (connectionType == "BT") 180 else 70
        player = LowLatencyPlayer(targetJitterMs = jitterMs).also { it.start() }

        setupWifiReceiverAndDiscovery()
        if (connectionType == "BT" && btAddress != null) {
            connectBluetooth(btAddress!!)
        }

        applyRole(role)
        peerIp?.let { setPeerIp(it) }

        // Status updater
        serviceScope.launch {
            while (started) {
                delay(2000)
                val tx = udpStreamer?.packetsSent ?: 0
                updateNotification(
                    "Peer:${peerIp ?: "?"} TX:$tx RX:$rxCount"
                )
            }
        }
    }

    private fun setupWifiReceiverAndDiscovery() {
        if (udpReceiver != null) return

        udpReceiver = UdpAudioReceiver { fromIp, seq, _, payload ->
            rxCount++
            // Auto peer from first packet
            if (peerIp == null || peerIp != fromIp) {
                setPeerIp(fromIp)
            }
            if (role == ROLE_RECEIVER || role == ROLE_BOTH) {
                player?.enqueue(seq, payload)
            }
        }
        udpReceiver?.start(serviceScope)

        discovery = DiscoveryHelper { ip ->
            if (peerIp == null) {
                setPeerIp(ip)
            }
        }
        discovery?.start(serviceScope)
    }

    @Suppress("MissingPermission")
    private fun connectBluetooth(address: String) {
        try {
            if (btHelper == null) {
                btHelper = BluetoothAudioHelper { pcm ->
                    rxCount++
                    if (role == ROLE_RECEIVER || role == ROLE_BOTH) {
                        player?.enqueue((System.nanoTime() % Int.MAX_VALUE).toInt(), pcm)
                    }
                }
                btHelper?.startServer(serviceScope)
            }
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            val device = adapter.getRemoteDevice(address)
            btHelper?.connectToDevice(device, serviceScope)
            updateNotification("BT connecting $address")
        } catch (e: Exception) {
            Log.e(TAG, "BT", e)
        }
    }

    private fun applyRole(newRole: String) {
        role = when (newRole) {
            ROLE_SENDER, ROLE_RECEIVER, ROLE_BOTH -> newRole
            else -> ROLE_BOTH
        }
        when (role) {
            ROLE_BOTH -> {
                stopSenderCapture()
                startSenderCapture()
                muteLocalSpeaker(false)
            }
            ROLE_SENDER -> {
                stopSenderCapture()
                startSenderCapture()
                muteLocalSpeaker(true)
            }
            ROLE_RECEIVER -> {
                stopSenderCapture()
                muteLocalSpeaker(false)
            }
        }
    }

    private fun startSenderCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val proj = mediaProjection ?: run {
            Log.e(TAG, "no projection")
            return
        }
        if (capturer != null) return

        capturer = SystemAudioCapturer(proj) { seq, pcm ->
            if (role != ROLE_SENDER && role != ROLE_BOTH) return@SystemAudioCapturer
            txHint++
            when (connectionType) {
                "WIFI" -> {
                    val ip = peerIp
                    if (ip != null) {
                        if (udpStreamer == null) {
                            udpStreamer = UdpAudioStreamer(ip).also { it.start() }
                        }
                        udpStreamer?.sendWithSeq(seq, pcm)
                    }
                }
                "BT" -> btHelper?.send(pcm)
            }
        }
        capturer?.start(serviceScope)
        Log.i(TAG, "CAPTURE ON")
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
        started = false
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
        try { if (multicastLock?.isHeld == true) multicastLock?.release() } catch (_: Exception) {}
        wakeLock = null
        multicastLock = null
        peerIp = null
        rxCount = 0
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
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopEverything()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        serviceScope.cancel()
        super.onDestroy()
    }
}
